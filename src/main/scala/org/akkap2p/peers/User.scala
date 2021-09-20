package org.akkap2p
package peers

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.ws._
import com.typesafe.scalalogging.StrictLogging

object User extends StrictLogging {

  sealed trait Command

  final case class AcceptConnection(sender: ActorRef[HttpResponse], upgrade: WebSocketUpgrade, address: Address, onReceive: String => Unit) extends Command
  final case class RequestConnection(address: Address, onReceive: String => Unit, timeout: Duration) extends Command
  final case class Connected(address: Address, peer: ActorRef[Peer.Command]) extends Command

  final case class Send(address: Address, message: String) extends Command
  final case class Broadcast(message: String) extends Command

  final case class Disconnect(address: Address) extends Command
  case object DisconnectAll extends Command

  final case class PeerGroup(name: String, addresses: Set[Address])
  final case class GetPeers(sender: ActorRef[Set[PeerGroup]]) extends Command

  def behavior(localPort: Int, connectionEndpoint: String): Behavior[Command] = {

    def uriString(address: Address): String = s"ws://$address/$connectionEndpoint?port=$localPort"

    def withPeers(connected: Map[Address, ActorRef[Peer.Command]], disconnected: Map[Address, ActorRef[Peer.Command]]): Behavior[Command] = {
      Behaviors.receive { (context, command) =>

        implicit val system: ActorSystem[Nothing] = context.system
        implicit val scheduler: Scheduler = system.scheduler
        implicit val ec: ExecutionContextExecutor = system.executionContext

        command match {
          case AcceptConnection(originalSender, upgrade, address, onReceive) =>
            disconnected.get(address) match {
              case Some(child) =>
                logger.info(s"Attempting to accept connection from known peer at $address")
                child ! Peer.AcceptConnection(originalSender, upgrade, onReceive)
                Behaviors.same

              case None =>
                if (connected.contains(address)) {
                  logger.warn(s"Cannot accept connection from already-connected peer at $address")
                  Behaviors.same

                } else {
                  val peer = context.spawn(Peer.disconnected(context.self, address), address.urlEncoded)
                  peer ! Peer.AcceptConnection(originalSender, upgrade, onReceive)
                  withPeers(connected, disconnected + (address -> peer))
                }
            }

          case RequestConnection(address, onReceive, timeout) =>
            disconnected.get(address) match {
              case Some(child) =>
                logger.info(s"Attempting to connect to known peer at $address")
                child ! Peer.RequestConnection(uriString, timeout, onReceive)
                Behaviors.same

              case None =>
                if (connected.contains(address)) {
                  logger.warn(s"Cannot connect to already-connected peer at $address")
                  Behaviors.same

                } else {
                  val peer = context.spawn(Peer.disconnected(context.self, address), address.urlEncoded)
                  peer ! Peer.RequestConnection(uriString, timeout, onReceive)
                  withPeers(connected, disconnected + (address -> peer))
                }
            }

          case Connected(address: Address, peer: ActorRef[Peer.Command]) =>
            logger.info(s"Registering connected peer at $address")
            withPeers(connected + (address -> peer), disconnected - address)

          case Broadcast(message) =>
            connected.values.foreach(_ ! Peer.Outgoing(message))
            Behaviors.same

          case DisconnectAll =>
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