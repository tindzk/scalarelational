package org.scalarelational

import org.scalarelational.model.Datastore

import scala.concurrent._


trait SessionSupport {
  this: Datastore =>

  protected def executionContext = ExecutionContext.global

  protected val _session = new ThreadLocal[Session]
  def session = _session.get() match {
    case null => throw new RuntimeException(s"No session defined in the current thread. SQL calls must be executed in a session or transaction block.")
    case c => c
  }
  def connection = session.connection
  def hasSession = _session.get() != null

  protected def createSession() = if (hasSession) {
    false
  } else {
    _session.set(instantiateSession())
    true
  }

  protected def instantiateSession(): Session = Session(this)

  protected def disposeSession() = {
    if (!hasSession) throw new RuntimeException(s"No context currently exists in current thread...cannot dispose.")
    session.dispose()
    _session.remove()
  }

  protected def createTransaction() = if (session.inTransaction) {
    false
  } else {
    connection.setAutoCommit(false)
    session.inTransaction = true
    true
  }

  protected def commitTransaction() = {
    connection.commit()
    connection.setAutoCommit(false)
    session.inTransaction = false
  }

  protected def rollbackTransaction() = {
    connection.rollback()
    connection.setAutoCommit(false)
    session.inTransaction = false
  }

  def withSession[Result](f: Session => Result): Result = {
    val created = createSession()
    val session = this.session
    try {
      f(session)
    } finally {
      if (created) disposeSession()
    }
  }

  def transaction[Result](f: Session => Result): Result = withSession { session =>
    val created = createTransaction()
    try {
      val result: Result = f(session)
      if (created) commitTransaction()
      result
    } catch {
      case t: Throwable => {
        if (created) rollbackTransaction()
        throw t
      }
    }
  }

  /**
   * Executes the inline function asynchronously and surrounds in a session returning Future[Result].
   */
  def async[Result](f: Session => Result) = Future({
    withSession { session =>
      f(session)
    }
  })(executionContext)
}
