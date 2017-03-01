package org.http4s
package server
package middleware

import org.http4s.util.threads.threadFactory

import scala.concurrent.duration._
import scalaz.concurrent.Task
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scalaz.\/-
import scalaz.syntax.monad._

object Timeout {

  // TODO: this should probably be replaced with a low res executor to save clock cycles
  private val defaultScheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(
    1, threadFactory(name = l => s"http4s-timeout-scheduler-$l", daemon = true))

  val DefaultTimeoutResponse = Response(Status.InternalServerError)
    .withBody("The service timed out.")

  private def timeoutResp(timeout: Duration, response: Task[Response], customScheduler: Option[ScheduledExecutorService]): Task[Response] = Task.async[Task[Response]] { cb =>
    val r = new Runnable { override def run(): Unit = cb(\/-(response)) }
    val scheduler = customScheduler.getOrElse(defaultScheduler)
    scheduler.schedule(r, timeout.toNanos, TimeUnit.NANOSECONDS)
    ()
  }.join

  /** Transform the service such to return whichever resolves first:
    * the provided Task[Response], or the result of the service
    * @param r Task[Response] to race against the result of the service. This will be run for each [[Request]]
    * @param service [[org.http4s.HttpService]] to transform
    */
  def apply(r: Task[Response])(service: HttpService): HttpService = Service.lift { req =>
    Task.taskInstance.chooseAny(service(req), r :: Nil).map(_._1)
  }

  /** Transform the service to return a RequestTimeOut [[Status]] after the supplied Duration
    * @param timeout Duration to wait before returning the RequestTimeOut
    * @param customScheduler a custom scheduler to use for timing out the request.
    *   If `None` is provided, then a default daemon thread scheduler will be used.
    * @param service [[HttpService]] to transform
    */
  def apply(timeout: Duration, response: Task[Response] = DefaultTimeoutResponse, customScheduler: Option[ScheduledExecutorService] = None)(service: HttpService): HttpService = {
    if (timeout.isFinite()) apply(timeoutResp(timeout, response, customScheduler))(service)
    else service
  }

  /** Transform the service to return a RequestTimeOut [[Status]] after 30 seconds
    * @param service [[HttpService]] to transform
    */
  @deprecated("Use the overload that also has response and customScheduler parameters (with default values provided)", "0.15")
  def apply(service: HttpService): HttpService = apply(30.seconds)(service)
}