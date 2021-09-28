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
import akka.stream.{KillSwitches, OverflowStrategy, SubscriptionWithCancelException, UniqueKillSwitch}
import com.typesafe.scalalogging.StrictLogging
import org.akkap2p.model.{Address, AddressedMessage}

/**
 * The `Peer` actor represents other users of `akka-p2p`.
 *
 * The `Peer` can request connections from the `User`, which responds with a `ConnectionAccepted`.
 *
 * The `User` can request connections to the `Peer` by sending it a `ConnectionRequested` message.
 *
 * The `User` can tell the `Peer` that its closing the connection by sending it a `Disconnect` message.
 *
 * Messages to the `Peer` are sent to it as `Outgoing` messages.
 *
 * Messages from the `Peer` are received as `Incoming` messages and handled with the `onReceive` behavior.
 */
object Peer extends StrictLogging {

  private val ClosingConnection = "[[[goodbye"
  private val ClosingConnectionAck = "adios]]]"

  sealed trait Command

  /** Tell the external `Peer` that this `User` has accepted their connection request. */
  final case class ConnectionAccepted(origin: ActorRef[HttpResponse], upgrade: WebSocketUpgrade, onReceive: AddressedMessage => Unit) extends Command

  /** Tell the external `Peer` that this `User` has requested a connection. */
  final case class ConnectionRequested(timeout: Duration, onReceive: AddressedMessage => Unit) extends Command

  /** Tell the external `Peer` that this `User` is closing the connection. */
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
   * handles a [[ConnectionRequested]] or [[ConnectionAccepted]] message.
   *
   * A `disconnected` `Peer` ignores all `Command`s except `ConnectionRequested` and `ConnectionAccepted`.
   *
   * @param address the [[Address]] (`host` and `port`) of this `Peer`
   * @return a `Peer` actor `Behavior`, either `connected` or `disconnected`
   */
  def disconnected(address: Address)(implicit user: ActorRef[User.Command], config: Main.Config): Behavior[Command] =
    Behaviors.receive { (context, command) =>

      implicit val system: ActorSystem[Nothing] = context.system

      def refSourceSinkSwitch: (ActorRef[TMS], Source[TMS, NotUsed], Sink[Message, NotUsed], UniqueKillSwitch) = {

        val completeOn = Map(TMS(ClosingConnectionAck) -> ())
        val actorSource = ActorSource.actorRef[TMS](completeOn, Map.empty, 10, OverflowStrategy.dropBuffer)
        val killableSource = actorSource.viaMat(KillSwitches.single)(Keep.both)
        val ((actorRef, killSwitch), source) = killableSource.preMaterialize()

        val actorSink = ActorSink.actorRef[Command](context.self, StreamHasCompleted, StreamHasFailed)

        // only forward TextMessage.Strict messages to actor, discard all others
        val sink = Flow.fromFunction[Message, Option[String]] {
          case TMS(text) => Some(text)
          case _ => None
        }.collect({ case Some(string) => Incoming(string) }).toMat(actorSink)(Keep.none)

        (actorRef, source, sink, killSwitch)
      }

      command match {
        case ConnectionRequested(timeout, onReceive) =>
          logger.debug(s"Disconnected peer at $address received connection request from user")

          val uri = Uri(s"ws://$address/${API.ConnectionEndpoint}?port=${config.httpPort}")
          val (peer, source, sink, killSwitch) = refSourceSinkSwitch
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
                connected(address, onReceive, peer, killSwitch)

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

        case ConnectionAccepted(origin, upgrade, onReceive) =>
          logger.debug(s"Accepted connection request from disconnected Peer at $address")
          val (peer, source, sink, killSwitch) = refSourceSinkSwitch
          origin ! upgrade.handleMessagesWithSinkSource(sink, source)
          user ! User.RegisterConnected(address, context.self)
          connected(address, onReceive, peer, killSwitch)

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
  private[this] def disconnecting(address: Address, killSwitch: UniqueKillSwitch)(implicit user: ActorRef[User.Command], config: Main.Config): Behavior[Command] =
    Behaviors.receiveMessage {
      case Disconnect =>
        logger.info(s"Disconnected from peer at $address")
        disconnected(address)

      case StreamHasCompleted =>
        logger.debug("Connection confirmed closed")
        killSwitch.shutdown()
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
  private[this] def connected(address: Address, onReceive: AddressedMessage => Unit, peer: ActorRef[TMS], killSwitch: UniqueKillSwitch
                             )(implicit user: ActorRef[User.Command], config: Main.Config): Behavior[Command] =
    Behaviors.receiveMessage {
      case Incoming(message) =>
        logger.debug(s"""Received incoming message "$message" from $address""")
        if (message == ClosingConnection) {
          logger.debug(s""""$ClosingConnection" is the "ClosingConnection" message -- disconnecting""")
          peer ! TextMessage.Strict(ClosingConnectionAck)
          user ! User.Disconnect(address)
          disconnecting(address, killSwitch)
        } else {
          onReceive(AddressedMessage(address, message))
          Behaviors.same
        }

      case Outgoing(message) =>
        logger.debug(s"""Sending message "$message" to $address""")
        peer ! TextMessage.Strict(message)
        Behaviors.same

      case Disconnect =>
        logger.info(s"Telling peer at $address that we're closing this connection")
        peer ! TextMessage.Strict(ClosingConnection)
        disconnecting(address, killSwitch)

      case StreamHasCompleted =>
        logger.info(s"Connection to $address has been closed")
        user ! User.Disconnect(address)
        disconnecting(address, killSwitch)

      case StreamHasFailed(throwable) =>
        logger.warn(s"Connection to $address has failed. Disconnecting.", throwable)
        user ! User.Disconnect(address)
        disconnecting(address, killSwitch)

      case _: ConnectionRequested =>
        logger.warn(s"Cannot connect to already-connected peer at $address")
        Behaviors.same

      case other =>
        logger.warn(s"Connected Peer received unexpected Command: $other")
        Behaviors.same
    }

}
