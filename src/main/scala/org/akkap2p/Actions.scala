package org.akkap2p

import scala.concurrent.duration.DurationInt

import akka.actor.typed.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import org.akkap2p.peers.{Address, User}

object Actions extends StrictLogging {

  def connect(address: Address)(implicit system: ActorSystem[User.Command]): Unit = {
    logger.debug(s"Attempting to connect to peer at $address")
    system.ref ! User.RequestConnection(address, x => logger.info(x), 10.seconds)
  }

  def disconnect(address: Address)(implicit system: ActorSystem[User.Command]): Unit = {
    logger.debug(s"Attempting to disconnect from peer at $address")
    system.ref ! User.Disconnect(address)
  }

  def disconnectAll()(implicit system: ActorSystem[User.Command]): Unit = {
    logger.info("Attempting to disconnect from all peers")
    system.ref ! User.Disconnect
  }

  def send(address: Address, message: String)(implicit system: ActorSystem[User.Command]): Unit = {
    logger.debug(s"""Attempting to send message "$message" to peer at $address""")
    system.ref ! User.Send(address, message)
  }

  def broadcast(message: String)(implicit system: ActorSystem[User.Command]): Unit = {
    logger.debug(s"""Attempting to broadcast message "$message" to all peers""")
    system.ref ! User.Broadcast(message)
  }

}
