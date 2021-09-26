package org.akkap2p
package model

/** Represents a `message` to be sent to a `Peer` at a particular [[Address]]. */
final case class AddressedMessage(address: Address, message: String)
