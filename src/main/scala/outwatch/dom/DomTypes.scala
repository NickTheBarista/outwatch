package outwatch.dom

import cats.effect.{Effect, IO}
import com.raquo.domtypes.generic.defs.sameRefTags._
import com.raquo.domtypes.generic.defs.{attrs, props, reflectedAttrs, styles}
import com.raquo.domtypes.generic.{builders, codecs, keys}
import com.raquo.domtypes.jsdom.defs.eventProps
import monix.execution.{Ack, Cancelable}
import monix.reactive.OverflowStrategy.Unbounded
import org.scalajs.dom
import outwatch.dom.helpers._

import scala.scalajs.js

trait DomTypesFactory[F[+_]]
  extends VDomModifierFactory[F]
    with BuilderFactory[F]
    with EmitterFactory[F]
    with AttributesFactory[F]
    with CompatFactory[F]
{

  implicit val effectF:Effect[F]

  private[outwatch] object BuilderTypes {
    type Attribute[T, _] = AttributeBuilder[T, Attr]
    type Property[T, _] = PropBuilder[T]
    type EventEmitter[E <: dom.Event] = SimpleEmitterBuilder[E, Emitter]
  }

  private[outwatch] object CodecBuilder {
    def encodeAttribute[V](codec: codecs.Codec[V, String]): V => Attr.Value = codec match {
      //The BooleanAsAttrPresenceCodec does not play well with snabbdom. it
      //encodes true as "" and false as null, whereas snabbdom needs true/false
      //of type boolean (not string) for toggling the presence of the attribute.
      case _: codecs.BooleanAsAttrPresenceCodec.type => identity
      case _ => codec.encode
    }
  }

  // Tags

  private[outwatch] trait TagBuilder extends builders.TagBuilder[TagBuilder.Tag, VTree] {

    // we can ignore information about void tags here, because snabbdom handles this automatically for us based on the tagname.
    protected override def tag[Ref <: VTree](tagName: String, void: Boolean): VTree = VTree(tagName, Seq.empty)
  }

  private[outwatch] object TagBuilder {
    type Tag[T] = VTree
  }

  trait Tags
    extends TagBuilder
      with EmbedTags[TagBuilder.Tag, VTree]
      with GroupingTags[TagBuilder.Tag, VTree]
      with TextTags[TagBuilder.Tag, VTree]
      with FormTags[TagBuilder.Tag, VTree]
      with SectionTags[TagBuilder.Tag, VTree]
      with TableTags[TagBuilder.Tag, VTree]
      with TagHelpers

  trait TagsExtra
    extends MiscTags[TagBuilder.Tag, VTree]
      with DocumentTags[TagBuilder.Tag, VTree] {
    this: TagBuilder =>
  }

  // all Attributes

  trait Attributes
    extends Attrs
      with ReflectedAttrs
      with Props
      with Events
      with AttributeHelpers
      with OutwatchAttributes
      with AttributesCompat

  // Attrs
  trait Attrs
    extends attrs.Attrs[BasicAttrBuilder]
      with builders.AttrBuilder[BasicAttrBuilder] {

    override protected def attr[V](key: String, codec: codecs.Codec[V, String]): BasicAttrBuilder[V] =
      new BasicAttrBuilder(key, CodecBuilder.encodeAttribute(codec))
  }

  // Reflected attrs
  trait ReflectedAttrs
    extends reflectedAttrs.ReflectedAttrs[BuilderTypes.Attribute]
      with builders.ReflectedAttrBuilder[BuilderTypes.Attribute] {

    // super.className.accum(" ") would have been nicer, but we can't do super.className on a lazy val
    override lazy val className = new AccumAttrBuilder[String]("class",
      stringReflectedAttr(attrKey = "class", propKey = "className"),
      _ + " " + _
    )

    override protected def reflectedAttr[V, DomPropV](
                                                       attrKey: String,
                                                       propKey: String,
                                                       attrCodec: codecs.Codec[V, String],
                                                       propCodec: codecs.Codec[V, DomPropV]
                                                     ): BasicAttrBuilder[V] = new BasicAttrBuilder(attrKey, CodecBuilder.encodeAttribute(attrCodec))

    //or: new PropertyBuilder(propKey, propCodec.encode)
  }

  // Props
  trait Props
    extends props.Props[BuilderTypes.Property]
      with builders.PropBuilder[BuilderTypes.Property] {

    override protected def prop[V, DomV](key: String, codec: codecs.Codec[V, DomV]): PropBuilder[V] =
      new PropBuilder(key, codec.encode)
  }


  // Events
  trait Events
    extends eventProps.HTMLElementEventProps[BuilderTypes.EventEmitter]
      with builders.EventPropBuilder[BuilderTypes.EventEmitter, dom.Event]
      with EmitterBuilderFactory {

    override def eventProp[V <: dom.Event](key: String): BuilderTypes.EventEmitter[V] = emitterBuilderFactory(key)
  }


  // Window / Document events

  private[outwatch] abstract class ObservableEventPropBuilder(target: dom.EventTarget)
    extends builders.EventPropBuilder[Observable, dom.Event] {
    override def eventProp[V <: dom.Event](key: String): Observable[V] = Observable.create(Unbounded) { obs =>
      val eventHandler: js.Function1[V, Ack] = obs.onNext _
      target.addEventListener(key, eventHandler)
      Cancelable(() => target.removeEventListener(key, eventHandler))
    }
  }

  abstract class WindowEvents
    extends ObservableEventPropBuilder(dom.window)
      with eventProps.WindowEventProps[Observable]

  abstract class DocumentEvents
    extends ObservableEventPropBuilder(dom.document)
      with eventProps.DocumentEventProps[Observable]

  // Styles

  private[outwatch] trait SimpleStyleBuilder extends builders.StyleBuilders[F[Style]] {

    override protected def buildDoubleStyleSetter(style: keys.Style[Double], value: Double): F[Style] =
      new BasicStyleBuilder[Any](style.cssName) := value

    override protected def buildIntStyleSetter(style: keys.Style[Int], value: Int): F[Style] =
      new BasicStyleBuilder[Any](style.cssName) := value

    override protected def buildStringStyleSetter(style: keys.Style[_], value: String): F[Style] =
      new BasicStyleBuilder[Any](style.cssName) := value
  }

  trait Styles
    extends SimpleStyleBuilder
      with styles.Styles[F[Style]]

  trait StylesExtra
    extends SimpleStyleBuilder
      with styles.Styles2[F[Style]]

}

@deprecated("Use dsl.tags instead", "0.11.0")
object Tags extends DomTypesFactory[IO] {
  implicit val effectF: Effect[IO] = IO.ioConcurrentEffect
}

@deprecated("Use dsl.attributes instead", "0.11.0")
object Attributes extends DomTypesFactory[IO] {
  implicit val effectF: Effect[IO] = IO.ioConcurrentEffect
}

