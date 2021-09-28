package org.akkap2p

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern.Askable
import com.typesafe.scalalogging.StrictLogging
import org.akkap2p.Main.Config
import org.akkap2p.actors.User
import org.akkap2p.model.{Address, AddressedMessage}

object Actions extends StrictLogging {

  def connect(address: Address, onReceive: AddressedMessage => Unit)(implicit system: ActorSystem[User.Command], config: Config): Option[String] = {
    val msg = s"Attempting to request connect to peer at $address"
    logger.debug(msg)
    system.ref ! User.RequestConnection(address, onReceive, config.timeouts.requestConnection)
    Some(msg)
  }

  def disconnect(address: Address)(implicit system: ActorSystem[User.Command]): Option[String] = {
    val msg = s"Attempting to disconnect from peer at $address"
    logger.debug(msg)
    system.ref ! User.Disconnect(address)
    Some(msg)
  }

  def disconnectAll()(implicit system: ActorSystem[User.Command]): Option[String] = {
    val msg = "Attempting to disconnect from all peers"
    logger.info(msg)
    system.ref ! User.Disconnect
    Some(msg)
  }

  def send(address: Address, message: String)(implicit system: ActorSystem[User.Command]): Option[String] = {
    val msg =s"""Attempting to send message "$message" to peer at $address"""
    logger.debug(msg)
    system.ref ! User.Send(address, message)
    Some(msg)
  }

  def broadcast(message: String)(implicit system: ActorSystem[User.Command]): Option[String] = {
    val msg =s"""Attempting to broadcast message "$message" to all peers"""
    logger.debug(msg)
    system.ref ! User.Broadcast(message)
    Some(msg)
  }

}
