package org.akkap2p
package model

/** Represents a message from or to a [[actors.Peer Peer]]. */
final case class AddressedMessage(address: Address, message: String)
