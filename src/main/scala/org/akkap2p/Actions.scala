package org.akkap2p

import akka.actor.typed.ActorRef
import com.typesafe.scalalogging.StrictLogging
import org.akkap2p.actors.User
import org.akkap2p.model.{Address, AddressedMessage}

/**
 * Actions represent routines which can be called via the [[Terminal]] or via the [[API]].
 */
object Actions extends StrictLogging {

  def connect(address: Address, onReceive: AddressedMessage => Unit)(implicit user: ActorRef[User.Command]): Option[String] = {
    val msg = s"Attempting to request connect to peer at $address"
    logger.debug(msg)
    user ! User.RequestConnection(address, onReceive)
    Some(msg)
  }

  def disconnect(address: Address)(implicit user: ActorRef[User.Command]): Option[String] = {
    val msg = s"Attempting to disconnect from peer at $address"
    logger.debug(msg)
    user ! User.Disconnect(address)
    Some(msg)
  }

  def logout()(implicit user: ActorRef[User.Command]): Option[String] = {
    val msg = "Attempting to disconnect from all peers (logout)"
    logger.info(msg)
    user ! User.Disconnect
    Some(msg)
  }

  def send(address: Address, message: String)(implicit user: ActorRef[User.Command]): Option[String] = {
    val msg =s"""Attempting to send message "$message" to peer at $address"""
    logger.debug(msg)
    user ! User.Send(address, message)
    Some(msg)
  }

  def broadcast(message: String)(implicit user: ActorRef[User.Command]): Option[String] = {
    val msg =s"""Attempting to broadcast message "$message" to all peers"""
    logger.debug(msg)
    user ! User.Broadcast(message)
    Some(msg)
  }

}
