package org.akkap2p
package peers

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.http.scaladsl.model.ws._
import com.typesafe.scalalogging.StrictLogging


object User extends StrictLogging {

  sealed trait Command

  final case class AcceptConnection(sender: ActorRef[ActorRef[Peer.Command]], address: Address, onReceive: String => Unit, peer: ActorRef[TextMessage.Strict]) extends Command
  final case class RequestConnection(address: Address, onReceive: String => Unit, timeout: Duration) extends Command

  final case class Broadcast(message: String) extends Command

  final case class Disconnect(address: Address) extends Command
  case object DisconnectAll extends Command

  final case class PeerGroup(name: String, addresses: Set[Address])
  final case class GetPeers(sender: ActorRef[Set[PeerGroup]]) extends Command

  def behavior(localPort: Int, connectionEndpoint: String): Behavior[Command] = {

    def uri(address: Address): String = s"ws://$address/$connectionEndpoint?port=$localPort"

    def withPeers(connected: Map[Address, ActorRef[Peer.Command]], disconnected: Map[Address, ActorRef[Peer.Command]]): Behavior[Command] = {
      Behaviors.receive { (context, command) =>

        implicit val system: ActorSystem[Nothing] = context.system

        command match {
          case AcceptConnection(sender, address, onReceive, peer) =>
            Try {
              context.spawn(Peer.connected(context.self, address, onReceive, peer), address.urlEncoded)
            } match {
              case Failure(exception) =>
                logger.error(s"Cannot accept connection from $address because", exception)
                Behaviors.same

              case Success(peer) =>
                logger.info(s"Successfully connected to $address")
                sender ! peer
                withPeers(connected + (address -> peer), disconnected)
            }

          case RequestConnection(address, onReceive, timeout) =>
            disconnected.get(address) match {
              case Some(child) =>
                logger.info(s"Attempting to reconnect to known peer at $address")
                child ! Peer.Connect(uri, timeout, onReceive)
                Behaviors.same

              case None =>
                if (connected.contains(address)) {
                  logger.warn(s"Cannot connect to already-connected peer at $address")
                  Behaviors.same

                } else {
                  Try {
                    context.spawn(Peer.disconnected(context.self, address), address.urlEncoded)
                  } match {
                    case Failure(exception) =>
                      logger.error(s"Cannot request connection to $address because", exception)
                      Behaviors.same

                    case Success(peer) =>
                      peer ! Peer.Connect(uri, timeout, onReceive)
                      withPeers(connected + (address -> peer), disconnected)
                  }
                }
            }

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