package outwatch

import cats.effect.IO
import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.observers.Subscriber
import monix.reactive.subjects.PublishSubject
import monix.reactive.{Observable, Observer}

import scala.concurrent.Future

object Handler {
  def empty[T](implicit s: Scheduler):IO[Handler[T]] = IO(PublishSubject[T])

  def create[T]()(implicit s: Scheduler):IO[Handler[T]] = IO(PublishSubject[T])

  def create[T](seed:T)(implicit s: Scheduler):IO[Handler[T]] = IO {
      PublishSubject[T].transformObservable(_.startWith(seed :: Nil))
  }
}

object ProHandler {
  def apply[I,O](observable: Observable[O], observer:Observer[I]):ProHandler[I,O] = new Observable[O] with Observer[I] {
    override def onNext(elem: I): Future[Ack] = observer.onNext(elem)
    override def onError(ex: Throwable): Unit = observer.onError(ex)
    override def onComplete(): Unit = observer.onComplete()
    override def unsafeSubscribeFn(subscriber: Subscriber[O]): Cancelable = observable.unsafeSubscribeFn(subscriber)
  }
}
