package org.akkap2p
package peers

import scala.concurrent.Await
import scala.concurrent.duration._
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
import org.scalactic.TypeCheckedTripleEquals._

object Peer extends StrictLogging {

  private val ClosingConnection = "[[[goodbye"
  private val ClosingConnectionAck = "adios]]]"

  sealed trait Command

  final case class AcceptConnection(origin: ActorRef[HttpResponse], upgrade: WebSocketUpgrade, onReceive: String => Unit) extends Command
  final case class RequestConnection(uriString: Address => String, timeout: Duration, onReceive: String => Unit) extends Command
  case object Disconnect extends Command

  final case class Incoming(message: String) extends Command
  final case class Outgoing(message: String) extends Command

  case object StreamHasCompleted extends Command
  final case class StreamHasFailed(throwable: Throwable) extends Command

  /**
   * Given a [[Peer]] [[ActorRef]], this method returns everything needed to request a WebSocket connection to (or accept a
   * WebSocket connection from) that peer.
   *
   * This method returns the following values in a 3-tuple:
   * <ol>
   *   <li>An ActorRef[TextMessage.Strict] ("`actorRef`")</li>
   *   <li>A `Source[TextMessage.Strict, NotUsed]` ("`source`")</li>
   *   <li>A `Sink[Message, NotUsed]` ("`sink`")</li>
   * </ol>
   *
   * [[TMS]] messages received by the [[Sink]] will be re-wrapped as [[Peer.Incoming]] messages and
   * forwarded to the given `peer` `ActorRef`&#91;[[Peer.Command]]&#93; for processing. All other [[Message]]s are
   * ignored. The `peer` and `sink` are used in conjunction in this way to listen for and handle messages arriving from
   * the peer.
   *
   * Similarly, when sending messages to the peer, the `actorRef` will send `Message`s to the [[Source]], which will
   * forward those messages through the WebSocket connection to the peer. To send a message to a given peer, then, you
   * must send a [[Peer.Outgoing]] message to the `peer` `ActorRef`.
   *
   * The resulting flow can be visualized as:
   * {{{
   * (Letting)
   *
   *   M   == Message
   *   TMS == TextMessage.Strict
   *   I   == Peer.Incoming
   *   O   == Peer.Outgoing
   *
   *     +-------------------------------------------------------------+
   *     | Receiving messages from peer                                |
   *     |                                                             |
   * P   |  +---------+                         +-------------------+  |  U
   * E   |  | "sink"  |     (M), filtered to    |      "peer"       |  |  S
   * E  ~~> | Sink[M] | ~> (TMS), wrapped as ~> | ActorRef[Command] | ~~> E
   * R   |  |         |          (I)            |  (context.self)   |  |  R
   *     |  +---------+                         +-------------------+  |
   *     +-------------------------------------------------------------+
   *
   *     +-----------------------------------------------------------------+
   *     | Sending messages to peer                                        |
   *     |                                                                 |
   * P   |  +-------------+   +----------------+             +--------+    |   U
   * E   |  |   "source"  |   |   "actorRef"   |             |        |    |   S
   * E  <~~ | Source[TMS] |~<~|  ActorRef[TMS] | <~ (TMS) <~ | "peer" | <~(O)~ E
   * R   |  |             |   | (context.self) |             |        |    |   R
   *     |  +-------------+   +----------------+             +--------+    |
   *     +-----------------------------------------------------------------+
   * }}}
   *
   * In other words, once the connection is configured correctly, the user should only need to interact with the
   * "`peer`" `ActorRef` in order to interact with the peer -- handling `Peer.Incoming` messages <i>from</i> the peer and
   * sending `Peer.Outgoing` messages <i>to</i> the peer.
   *
   * @param peer the `ActorRef[Peer.Command]` used for communicating with the peer
   * @param materializer used to create the `actorRef` and `source` from an [[ActorSource.actorRef]]
   * @return components required for requesting a WebSocket connection to (or accepting a WebSocket connection from) a peer
   */
  private[Peer] def refSourceAndSink(peer: ActorRef[Command])(implicit materializer: ActorSystem[_]): (ActorRef[TMS], Source[TMS, NotUsed], Sink[Message, NotUsed]) = {

    val completeOn = Map(TMS(ClosingConnectionAck) -> ())
    val actorSource = ActorSource.actorRef[TMS](completeOn, Map.empty, 10, OverflowStrategy.dropBuffer)
    val (actorRef, source) = actorSource.preMaterialize()

    val actorSink = ActorSink.actorRef[Command](peer, StreamHasCompleted, StreamHasFailed)

    // only forward TextMessage.Strict messages to actor, discard all others
    val sink = Flow.fromFunction[Message, Option[String]] {
      case TMS(text) => Some(text)
      case _ => None
    }.collect({ case Some(string) => Incoming(string) }).toMat(actorSink)(Keep.none)

    (actorRef, source, sink)
  }

  /**
   * The [[Behavior]] of a disconnected [[Peer]].
   *
   * All `Peer`s start with this `disconnected` behavior. A `Peer` may become
   * [[connected]] after it receives and handles a [[RequestConnection]] or
   * [[AcceptConnection]] message.
   *
   * A `disconnected` `Peer` ignores all `Command`s except `RequestConnection`
   * and `AcceptConnection`.
   *
   * @param user the [[User]] actor which spawned this `Peer` actor
   * @param address the [[Address]] (hostname and port) of this `Peer`
   * @return a `Peer` actor `Behavior`, either `connected` or `disconnected`
   */
  def disconnected(user: ActorRef[User.Command], address: Address): Behavior[Command] =
    Behaviors.receive { (context, command) =>

      implicit val system: ActorSystem[Nothing] = context.system

      command match {
        case RequestConnection(uriString, timeout, onReceive) =>
          logger.debug(s"Disconnected Peer at $address received Command to RequestConnection")

          val (peer, source, sink) = refSourceAndSink(context.self)
          val webSocket = Http().webSocketClientFlow(WebSocketRequest(Uri(uriString(address))))
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
                user ! User.Connected(address, context.self)
                connected(user, address, onReceive, peer)

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
          val (peer, source, sink) = refSourceAndSink(context.self)
          origin ! upgrade.handleMessagesWithSinkSource(sink, source)
          user ! User.Connected(address, context.self)
          connected(user, address, onReceive, peer)

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
   * A `disconnecting` `Peer` ignores all `Command`s except a `Command` from the
   * `User` actor to `Disconnect`, which it handles by becoming `disconnected`.
   *
   * @param user the `User` actor which spawned this `Peer` actor
   * @param address the `Address` of this `Peer`
   * @return a `Peer` actor `Behavior`, either `disconnecting` or `disconnected`
   */
  private[Peer] def disconnecting(user: ActorRef[User.Command], address: Address): Behavior[Command] =
    Behaviors.receiveMessage {
      case Disconnect =>
        logger.info(s"Disconnected from peer at $address")
        disconnected(user, address)

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
   * We can receive and handle [[Incoming]] messages from a connected peer, and
   * send [[Outgoing]] messages to a connected peer. We can also [[Disconnect]]
   * from a connected peer by sending it the [[ClosingConnection]] message.
   *
   * When we _receive_ a [[ClosingConnection]] message _from_ a peer, we reply
   * with the [[ClosingConnectionAck]] message, to let the peer know that we
   * acknowledge the termination of the connection.
   *
   * @param user
   * @param address
   * @param onReceive
   * @param peer
   * @return
   */
  private[Peer] def connected(user: ActorRef[User.Command], address: Address, onReceive: String => Unit, peer: ActorRef[TMS]): Behavior[Command] =
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

      case _: RequestConnection =>
        logger.warn(s"Cannot connect to already-connected peer at $address")
        Behaviors.same

      case other =>
        logger.warn(s"Connected Peer received unexpected Command: $other")
        Behaviors.same
    }

}