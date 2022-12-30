package com.example.bank.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

//single bank account
object PersistentBankAccount {

  /**
   * Event sourcing: we store data in events
   * that is bits of data is comprise the journey to latest data
   *  -> it is costly to build
   *  -> high fault tolerance
   *  -> for auditing
   */

  //commands = messages
  /**
   * The sealed keyword is used to control the extension of classes and traits.
   * Declaring a class or trait as sealed restricts where we can define its subclasses
   * — we have to define them in the same source file.
   */

  /**
   * Scala case classes are just regular classes which are immutable by default and decomposable through pattern matching.
   * It uses equal method to compare instance structurally.
   * It does not use new keyword to instantiate object.
   * All the parameters listed in the case class are public and immutable by default.
   */
  sealed trait Command
  object Command{
    case class CreateBankAccount(user: String, currency: String, initialBalance: Double, replyTo: ActorRef[Response]) extends Command
    case class UpdateBalance(id: String, currency: String, amount: Double /* can be <0 */ , replyTo: ActorRef[Response]) extends Command
    case class GetBankAccount(id: String, replyTo: ActorRef[Response]) extends Command
  }

  //events = to persist to Cassandra It is an action that happened to the bank account
  trait Event
  case class BankAccountCreated(bankAccount: BankAccount) extends Event
  case class BalanceUpdated(amount: Double) extends Event

  //state
  case class BankAccount(id: String, user: String, currency: String,balance: Double)


  //responses
  sealed trait Response
  object Response{
    case class BankAccountCreatedResponse(id: String) extends Response
    case class BankAccountBalanceUpdatedResponse(maybeBankAccount: Option[BankAccount]) extends Response
    case class GetBankAccountResponse(maybeBankAccount: Option[BankAccount]) extends Response
  }

  //command handler = message handler => persist an event
  //event handler => update state
  //state

  import Command._
  import Response._

  /**
   * Command handler is a function from command
   * event handler is a function from event
   */
  val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] = (state, command) =>
    command match {

      /**
       * Sequence of operation
       *
       * bank creates me
       * bank send me CreateBankAccount
       * I persist BankAccountCreated
       * I update my state
       * reply back to bank with the BankAccountCreatedResponse
       * (the bank surface the response to the HTTP server)
       */
      case CreateBankAccount(user, currency, initialBalance, replyTo) =>
        val id = state.id
        Effect
          .persist(BankAccountCreated(BankAccount(id, user, currency, initialBalance))) //persisted into Cassandra
          .thenReply(replyTo)(_ => BankAccountCreatedResponse(id))

      /**
       * Sequence of operation
       *
       * Id and currency is auto fetched
       * amount is updated, either (added or removed from account)
       * if new balance is less than 0 send nothing as response
       * else send new updated balance as response
       */
      case UpdateBalance(_, _, amount, bank) =>
        val newBalance = state.balance + amount
        // check here for withdrawal
        if (newBalance < 0) //illegal
          Effect.reply(bank)(BankAccountBalanceUpdatedResponse(None))
        else
          Effect.persist(BalanceUpdated(amount))
          .thenReply(bank)( newState => BankAccountBalanceUpdatedResponse(Some(newState)))

      /**
       * This operation will give you the bank account details by ID
       */
      case GetBankAccount(_,bank) =>
        Effect.reply(bank)(GetBankAccountResponse(Some(state)))

    }

  val eventHandler: (BankAccount, Event) => BankAccount = (state, event) =>
    event match {
      case BankAccountCreated(bankAccount) =>
        bankAccount

      case BalanceUpdated(amount) =>
        state.copy(balance = state.balance + amount)
    }

  //EventSourcedBehavior, Behavior
  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, BankAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "", "", 0.0), // unused
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}

