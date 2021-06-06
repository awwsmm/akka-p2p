package org.akkap2p
package peers

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.ws._
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.stream.{OverflowStrategy, SubscriptionWithCancelException}
import com.typesafe.scalalogging.StrictLogging
import org.scalactic.TypeCheckedTripleEquals._

object Peer extends StrictLogging {

  private val ClosingConnection = "[[[goodbye"
  private val ClosingConnectionAck = "adios]]]"

  sealed trait Command

  final case class Connect(uri: Address => String, timeout: Duration, onReceive: String => Unit) extends Command
  case object Disconnect extends Command

  final case class Incoming(message: String) extends Command
  final case class Outgoing(message: String) extends Command

  case object StreamHasCompleted extends Command
  final case class StreamHasFailed(throwable: Throwable) extends Command

  /**
   * @param ref actor which will receive the messages sent to the returned `Sink`
   * @return a `Sink` which will send all `TextMessage.Strict` values received
   * to the provided `Peer` `ActorRef` as `Incoming` messages
   */
  def sink(ref: ActorRef[Command]): Sink[Message, NotUsed] = {
    val actorSink = ActorSink.actorRef[Command](ref, StreamHasCompleted, StreamHasFailed)

    // only forward TextMessage.Strict messages to actor, discard all others
    Flow.fromFunction[Message, Option[String]] {
      case TextMessage.Strict(text) => Some(text)
      case _ => None
    }.collect({ case Some(string) => Incoming(string) }).toMat(actorSink)(Keep.none)
  }

  // TODO finish documentation here and elsewhere
  //   -- (?) val completeOn = Map(TextMessage.Strict(ClosingConnectionAck) -> ())

  def refAndSource()(implicit system: ActorSystem[_]): (ActorRef[TextMessage.Strict], Source[TextMessage.Strict, NotUsed]) = {
    val actorSource = ActorSource.actorRef[TextMessage.Strict](
      { case TextMessage.Strict(ClosingConnectionAck) => },
      Map.empty,
      10,
      OverflowStrategy.dropBuffer
    )

    actorSource.preMaterialize()
  }

  /**
   * `Behavior` of a `Peer` which is currently disconnected.
   *
   * A `disconnected` `Peer` ignores all messages except requests to `Connect`.
   *
   * @param user the `User` actor which spawned this `Peer` actor
   * @param address the `Address` of this `Peer`
   * @return a `Peer` actor `Behavior`, either `connected` or `disconnected`
   */
  def disconnected(user: ActorRef[User.Command], address: Address): Behavior[Command] =
    Behaviors.receive { (context, command) =>

      implicit val system: ActorSystem[Nothing] = context.system

      command match {
        case Connect(uri, timeout, onReceive) =>
          logger.debug(s"Disconnected Peer at $address received Command to Connect")

          val (actorRef, source) = refAndSource()
          val webSocket = Http().webSocketClientFlow(WebSocketRequest(Uri(uri(address))))
          val upgradeResponse = source.viaMat(webSocket)(Keep.right).toMat(sink(context.self))(Keep.left).run()

          Try {
            Await.result(upgradeResponse, timeout) match {
              case _: ValidUpgrade =>
                logger.info(s"Successfully connected to $address")
                connected(user, address, onReceive, actorRef)

              case InvalidUpgradeResponse(_, cause) =>
                logger.error(s"Received InvalidUpgradeResponse from $address: $cause")
                Behaviors.same[Command]
            }
          } match {
            case Failure(exception) =>
              logger.error(s"Encountered Exception when attempting to connect to $address", exception)
              Behaviors.same

            case Success(behavior) => behavior
          }

        case StreamHasFailed(SubscriptionWithCancelException.StageWasCompleted) =>
          logger.debug("Connection closed by peer")
          Behaviors.same

        case other =>
          logger.warn(s"Disconnected Peer received unexpected Command: $other")
          Behaviors.same
      }
    }

  /**
   * Behavior of a Peer which is currently disconnecting.
   *
   * A `disconnecting` `Peer` ignores all messages except a command from the
   * `User` actor to `Disconnect`, which it handles by becoming `disconnected`.
   *
   * @param user the `User` actor which spawned this `Peer` actor
   * @param address the `Address` of this `Peer`
   * @return a `Peer` actor `Behavior`, either `disconnecting` or `disconnected`
   */
  def disconnecting(user: ActorRef[User.Command], address: Address): Behavior[Command] =
    Behaviors.receiveMessage {
      case Disconnect =>
        logger.info(s"Disconnected from peer at $address")
        disconnected(user, address)

      case StreamHasCompleted =>
        logger.debug("Connection confirmed closed -- becoming disconnected")
        Behaviors.same

      case other =>
        logger.warn(s"Disconnecting Peer received unexpected Command: $other")
        Behaviors.same
    }

  // TODO documentation
  def connected(user: ActorRef[User.Command], address: Address, onReceive: String => Unit, peer: ActorRef[TextMessage.Strict]): Behavior[Command] =
    Behaviors.receiveMessage {
      case Incoming(message) =>
        logger.debug(s"""Received incoming message "$message" from $address""")
        if (message === ClosingConnection) {
          logger.debug(s""""$ClosingConnection" is the "ClosingConnection" message -- disconnecting""")
          peer ! TextMessage.Strict(ClosingConnectionAck)
          user ! User.Disconnect(address)
          disconnecting(user, address)
        } else {
          onReceive(message)
          Behaviors.same
        }

      case Outgoing(message) =>
        logger.debug(s"""Sending message "$message" to $address""")
        peer ! TextMessage.Strict(message)
        Behaviors.same

      case _: Connect =>
        logger.warn(s"Cannot connect to already-connected peer at $address")
        Behaviors.same

      case Disconnect =>
        logger.info(s"Telling peer at $address that we're closing this connection")
        peer ! TextMessage.Strict(ClosingConnection)
        disconnecting(user, address)

      case StreamHasCompleted =>
        logger.info(s"Connection to $address has been closed")
        user ! User.Disconnect(address)
        disconnecting(user, address)

      case StreamHasFailed(throwable) =>
        logger.warn(s"Connection to $address has failed. Disconnecting.", throwable)
        user ! User.Disconnect(address)
        disconnecting(user, address)
    }

}