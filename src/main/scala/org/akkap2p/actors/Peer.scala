package org.akkap2p
package actors

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.TextMessage.{Strict => TMS}
import akka.http.scaladsl.model.ws._
import akka.http.scaladsl.model.{HttpResponse, Uri}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.typed.scaladsl.{ActorSink, ActorSource}
import akka.stream.{OverflowStrategy, SubscriptionWithCancelException}
import com.typesafe.scalalogging.StrictLogging
import org.akkap2p.model.Address
import org.scalactic.TypeCheckedTripleEquals._

object Peer extends StrictLogging {

  private val user = Main.user
  private val config = Main.config

  private val ClosingConnection = "[[[goodbye"
  private val ClosingConnectionAck = "adios]]]"

  sealed trait Command

  /** `Command` the external `Peer` to accept the connection from this `User`. */
  final case class AcceptConnection(origin: ActorRef[HttpResponse], upgrade: WebSocketUpgrade, onReceive: String => Unit) extends Command

  /** `Command` the external `Peer` to request a connection to this `User`. */
  final case class RequestConnection(timeout: Duration, onReceive: String => Unit) extends Command

  /** `Command` the external `Peer` to close its connection to this `User`. */
  case object Disconnect extends Command

  /** Represents a message from this `User` to the external `Peer`. */
  final case class Outgoing(message: String) extends Command

  /** Represents a message from the external `Peer` to this `User`. */
  private final case class Incoming(message: String) extends Command

  /** Indicates that the external `Peer` has cleanly disconnected from this `User`. */
  private case object StreamHasCompleted extends Command

  /** Indicates that the external `Peer` has uncleanly disconnected from this `User`. */
  private final case class StreamHasFailed(throwable: Throwable) extends Command

  /**
   * The [[Behavior]] of a disconnected [[Peer]].
   *
   * All `Peer`s start with this `disconnected` behavior. A `Peer` may become [[connected]] after it receives and
   * handles a [[RequestConnection]] or [[AcceptConnection]] message.
   *
   * A `disconnected` `Peer` ignores all `Command`s except `RequestConnection` and `AcceptConnection`.
   *
   * @param address the [[Address]] (`host` and `port`) of this `Peer`
   * @return a `Peer` actor `Behavior`, either `connected` or `disconnected`
   */
  def disconnected(address: Address): Behavior[Command] =
    Behaviors.receive { (context, command) =>

      implicit val system: ActorSystem[Nothing] = context.system

      def refSourceAndSink: (ActorRef[TMS], Source[TMS, NotUsed], Sink[Message, NotUsed]) = {

        val completeOn = Map(TMS(ClosingConnectionAck) -> ())
        val actorSource = ActorSource.actorRef[TMS](completeOn, Map.empty, 10, OverflowStrategy.dropBuffer)
        val (actorRef, source) = actorSource.preMaterialize()

        val actorSink = ActorSink.actorRef[Command](context.self, StreamHasCompleted, StreamHasFailed)

        // only forward TextMessage.Strict messages to actor, discard all others
        val sink = Flow.fromFunction[Message, Option[String]] {
          case TMS(text) => Some(text)
          case _ => None
        }.collect({ case Some(string) => Incoming(string) }).toMat(actorSink)(Keep.none)

        (actorRef, source, sink)
      }

      command match {
        case RequestConnection(timeout, onReceive) =>
          logger.debug(s"Disconnected Peer at $address received Command to RequestConnection")

          val uri = Uri(s"ws://$address/${API.ConnectionEndpoint}?port=${config.httpPort}")
          val (peer, source, sink) = refSourceAndSink
          val webSocket = Http().webSocketClientFlow(WebSocketRequest(uri))
          val upgradeResponse = source.viaMat(webSocket)(Keep.right).toMat(sink)(Keep.left).run()

          /*
        TODO: possibly clean this up

        We block here because we cannot return a Future[Behavior].

        If we need more throughput for this actor, we can onComplete the
        Future[Behavior] to send a message to this actor with a new Behavior,
        which it could then become.

        Alternatively, we could add a 'connecting' Behavior.
         */

          Try {
            Await.result(upgradeResponse, timeout) match {
              case _: ValidUpgrade =>
                logger.info(s"Successfully connected to $address")
                user ! User.RegisterConnected(address, context.self)
                connected(address, onReceive, peer)

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

        case AcceptConnection(origin, upgrade, onReceive) =>
          logger.debug(s"Disconnected Peer at $address received Command to AcceptConnection")
          val (peer, source, sink) = refSourceAndSink
          origin ! upgrade.handleMessagesWithSinkSource(sink, source)
          user ! User.RegisterConnected(address, context.self)
          connected(address, onReceive, peer)

        case StreamHasFailed(SubscriptionWithCancelException.StageWasCompleted) =>
          logger.debug("Connection closed by peer")
          Behaviors.same

        case StreamHasCompleted =>
          logger.debug("Connection confirmed closed")
          Behaviors.same

        case other =>
          logger.warn(s"Disconnected Peer received unexpected Command: $other")
          Behaviors.same
      }
    }

  /**
   * The [[Behavior]] of a [[Peer]] which is currently disconnecting.
   *
   * A `disconnecting` `Peer` ignores all `Command`s except `Disconnect`, which it handles by becoming `disconnected`.
   *
   * @param address the [[Address]] (`host` and `port`) of this `Peer`
   * @return a `Peer` actor `Behavior`, either `disconnecting` or `disconnected`
   */
  private[this] def disconnecting(address: Address): Behavior[Command] =
    Behaviors.receiveMessage {
      case Disconnect =>
        logger.info(s"Disconnected from peer at $address")
        disconnected(address)

      case StreamHasCompleted =>
        logger.debug("Connection confirmed closed")
        Behaviors.same

      case other =>
        logger.warn(s"Disconnecting Peer received unexpected Command: $other")
        Behaviors.same
    }

  /**
   * The [[Behavior]] of a connected peer.
   *
   * We can receive and handle [[Incoming]] messages from a connected peer, and send [[Outgoing]] messages to a
   * connected peer. We can also [[Disconnect]] from a connected peer by sending it the [[ClosingConnection]] message.
   *
   * When we _receive_ a [[ClosingConnection]] message _from_ a peer, we reply with the [[ClosingConnectionAck]]
   * message, to let the peer know that we acknowledge the termination of the connection.
   *
   * @param address   the [[Address]] (`host` and `port`) of this `Peer`
   * @param onReceive defines how incoming messages from this `Peer` should be processed
   * @param peer      messages to be sent to this `Peer` are sent to this actor
   * @return a `Peer` actor `Behavior`, either `connected` or `disconnecting`
   */
  private[this] def connected(address: Address, onReceive: String => Unit, peer: ActorRef[TMS]): Behavior[Command] =
    Behaviors.receiveMessage {
      case Incoming(message) =>
        logger.debug(s"""Received incoming message "$message" from $address""")
        if (message === ClosingConnection) {
          logger.debug(s""""$ClosingConnection" is the "ClosingConnection" message -- disconnecting""")
          peer ! TextMessage.Strict(ClosingConnectionAck)
          user ! User.Disconnect(address)
          disconnecting(address)
        } else {
          onReceive(message)
          Behaviors.same
        }

      case Outgoing(message) =>
        logger.debug(s"""Sending message "$message" to $address""")
        peer ! TextMessage.Strict(message)
        Behaviors.same

      case Disconnect =>
        logger.info(s"Telling peer at $address that we're closing this connection")
        peer ! TextMessage.Strict(ClosingConnection)
        disconnecting(address)

      case StreamHasCompleted =>
        logger.info(s"Connection to $address has been closed")
        user ! User.Disconnect(address)
        disconnecting(address)

      case StreamHasFailed(throwable) =>
        logger.warn(s"Connection to $address has failed. Disconnecting.", throwable)
        user ! User.Disconnect(address)
        disconnecting(address)

      case _: RequestConnection =>
        logger.warn(s"Cannot connect to already-connected peer at $address")
        Behaviors.same

      case other =>
        logger.warn(s"Connected Peer received unexpected Command: $other")
        Behaviors.same
    }

}
