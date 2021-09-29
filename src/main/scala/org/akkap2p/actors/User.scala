package org.akkap2p
package actors

import scala.concurrent.duration.Duration

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.ws.WebSocketUpgrade
import com.typesafe.scalalogging.StrictLogging
import org.akkap2p.model.{Address, AddressedMessage}

/**
 * The `User` actor represents the end-user of `akka-http`.
 *
 * The `User` can `RequestConnection`s to and `AcceptConnection`s from external `Peer`s.
 *
 * The `User` can also `Disconnect` from an individual `Peer` or `Disconnect` from all connected `Peer`s.
 *
 * The `User` can `Send` a `message` to an individual `Peer` or `Broadcast` a message to all connected `Peer`s.
 */
object User extends StrictLogging {

  sealed trait Command

  /** `Command` to accept a connection from an external peer. */
  final case class AcceptConnection(sender: ActorRef[HttpResponse], upgrade: WebSocketUpgrade, address: Address, onReceive: AddressedMessage => Unit) extends Command

  /** `Command` to attempt a connection to an external peer. */
  final case class RequestConnection(address: Address, onReceive: AddressedMessage => Unit, timeout: Duration) extends Command

  /** `Command` received when successfully connected to an external peer. */
  final case class RegisterConnected(address: Address, peer: ActorRef[Peer.Command]) extends Command

  /** `Command` to disconnect from the peer at the specified `address`. */
  final case class Disconnect(address: Address) extends Command

  /** `Command` to disconnect from all connected peers. */
  case object Disconnect extends Command

  /** `Command` to send a message to a peer at a specified address. */
  final case class Send(address: Address, message: String) extends Command

  /** `Command` to send the `message` to all connected peers. */
  final case class Broadcast(message: String) extends Command

  /**
   * A `PeerGroup` is a labeled group of `Peer`s.
   *
   * For example, all connected or all disconnected peers might be in a `PeerGroup`.
   *
   * `PeerGroup`s are not mutually exclusive; the same `Peer` may appear in several `PeerGroup`s.
   *
   * @param name      label applied to this group of `Peer`s
   * @param addresses the unordered set of `Address`es of this group of `Peer`s
   */
  final case class PeerGroup(name: String, addresses: Set[Address])

  /** `Command` to retrieve all known `Peer`s, sorted into `"connected"` and `"disconnected"` `PeerGroup`s. */
  final case class GetPeers(sender: ActorRef[Set[PeerGroup]]) extends Command

  /**
   * The [[Behavior]] of the [[User]].
   *
   * The [[User]] actor keeps an internal cache of `connected` and `disconnected` [[Peer]]s.
   *
   * @return the [[Behavior]] of the [[User]]
   */
  def behavior(implicit config: Config): Behavior[Command] = {

    def withPeers(connected: Map[Address, ActorRef[Peer.Command]], disconnected: Map[Address, ActorRef[Peer.Command]]): Behavior[Command] = {
      Behaviors.receive { (context, command) =>

        implicit val user: ActorRef[Command] = context.self

        command match {
          case AcceptConnection(originalSender, upgrade, address, onReceive) =>
            disconnected.get(address) match {
              case Some(peer) =>
                logger.info(s"Attempting to accept connection from known peer at $address")
                peer ! Peer.ConnectionAccepted(originalSender, upgrade, onReceive)
                Behaviors.same

              case None =>
                if (connected.contains(address)) {
                  logger.warn(s"Cannot accept connection from already-connected peer at $address")
                  Behaviors.same

                } else {
                  val peer = context.spawn(Peer.disconnected(address), address.urlEncoded)
                  logger.info(s"Attempting to accept connection from new peer at $address")
                  peer ! Peer.ConnectionAccepted(originalSender, upgrade, onReceive)
                  withPeers(connected, disconnected + (address -> peer))
                }
            }

          case RequestConnection(address, onReceive, timeout) =>
            disconnected.get(address) match {
              case Some(peer) =>
                logger.info(s"Attempting to connect to known peer at $address")
                peer ! Peer.ConnectionRequested(timeout, onReceive)
                Behaviors.same

              case None =>
                if (connected.contains(address)) {
                  logger.warn(s"Cannot connect to already-connected peer at $address")
                  Behaviors.same

                } else {
                  logger.info(s"Attempting to connect to new peer at $address")
                  val peer = context.spawn(Peer.disconnected(address), address.urlEncoded)
                  peer ! Peer.ConnectionRequested(timeout, onReceive)
                  withPeers(connected, disconnected + (address -> peer))
                }
            }

          case RegisterConnected(address: Address, peer: ActorRef[Peer.Command]) =>
            logger.info(s"Registering connected peer at $address")
            withPeers(connected + (address -> peer), disconnected - address)

          case Broadcast(message) =>
            connected.values.foreach(_ ! Peer.Outgoing(message))
            Behaviors.same

          case Disconnect =>
            connected.values.foreach(_ ! Peer.Disconnect)
            Behaviors.same

          case Disconnect(address) =>
            connected.get(address) match {
              case Some(peer) =>
                peer ! Peer.Disconnect
                val nowConnected = connected - address
                val nowDisconnected = disconnected + (address -> peer)
                withPeers(nowConnected, nowDisconnected)

              case None =>
                if (disconnected.contains(address)) {
                  logger.info(s"Cannot disconnect from currently-disconnected peer at $address")
                  Behaviors.same
                } else {
                  logger.info(s"Cannot disconnect from unknown peer at $address")
                  Behaviors.same
                }
            }

          case Send(address, message) =>
            connected.get(address) match {
              case Some(peer) =>
                peer ! Peer.Outgoing(message)
                Behaviors.same

              case None =>
                if (disconnected.contains(address)) {
                  logger.info(s"Cannot send message to currently-disconnected peer at $address")
                  Behaviors.same
                } else {
                  logger.info(s"Cannot send message to unknown peer at $address")
                  Behaviors.same
                }
            }

          case GetPeers(sender) =>
            val connectedPeers = PeerGroup("connected", connected.keySet)
            val disconnectedPeers = PeerGroup("disconnected", disconnected.keySet)
            sender ! Set(connectedPeers, disconnectedPeers)
            Behaviors.same
        }
      }
    }

    withPeers(Map.empty, Map.empty)
  }
}
