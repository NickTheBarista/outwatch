package outwatch

import cats.effect.IO
import monix.execution.Ack.Continue
import monix.reactive.subjects.PublishSubject
import monix.reactive.Observable
import org.scalajs.dom.{document, html}
import outwatch.dom.helpers._
import outwatch.dom._
import outwatch.dom.dsl._
import outwatch.Deprecated.IgnoreWarnings.initEvent
import snabbdom.{DataObject, hFunction}
import org.scalajs.dom.window.localStorage
import org.scalatest.Assertion
import scala.collection.immutable.Seq
import scala.scalajs.js
import scala.scalajs.js.JSON
import scala.collection.mutable

class OutWatchDomSpec extends JSDomAsyncSpec {

  "Properties" should "be separated correctly" in {
    val properties = Seq(
      Attribute("hidden", "true"),
      InsertHook(PublishSubject()),
      UpdateHook(PublishSubject()),
      InsertHook(PublishSubject()),
      DestroyHook(PublishSubject()),
      PrePatchHook(PublishSubject()),
      PostPatchHook(PublishSubject())
    )

    val SeparatedProperties(att, hooks, keys) = properties.foldRight(SeparatedProperties())((p, sp) => p :: sp)

    hooks.insertHooks.length shouldBe 2
    hooks.prePatchHooks.length shouldBe 1
    hooks.updateHooks.length shouldBe 1
    hooks.postPatchHooks.length shouldBe 1
    hooks.destroyHooks.length shouldBe 1
    att.attrs.length shouldBe 1
    keys.length shouldBe 0
  }

  "VDomModifiers" should "be separated correctly" in {

    val test = for {

      div ← div()
      seq ← Seq(
        div(),
        attributes.`class` := "blue",
        attributes.onClick(1) --> sideEffect{},
        attributes.hidden <-- Observable(false)
      ).sequence

    } yield {

      val mods = Seq(
        Attribute("class", "red"),
        EmptyModifier,
        Emitter("click", _ => Continue),
        StringModifier("Test"),
        div,
        CompositeModifier(seq),
        AttributeStreamReceiver("hidden",Observable())
      )

      val SeparatedModifiers(properties, emitters, receivers, Children.VNodes(nodes, hasStream)) =
        SeparatedModifiers.from(mods)

      emitters.emitters.length shouldBe 2
      receivers.length shouldBe 2
      properties.attributes.attrs.length shouldBe 2
      nodes.length shouldBe 3
      hasStream shouldBe false
    }

    test
  }

  it should "be separated correctly with children" in {

    val test = div().map { div ⇒

      val modifiers: Seq[Modifier] = Seq(
        Attribute("class","red"),
        EmptyModifier,
        Emitter("click", _ => Continue),
        Emitter("input",  _ => Continue),
        AttributeStreamReceiver("hidden",Observable()),
        AttributeStreamReceiver("disabled",Observable()),
        Emitter("keyup",  _ => Continue),
        StringModifier("text"),
        div
      )

      val SeparatedModifiers(properties, emitters, receivers, Children.VNodes(nodes, hasStream)) =
        SeparatedModifiers.from(modifiers)

      emitters.emitters.length shouldBe 3
      receivers.length shouldBe 2
      properties.attributes.attrs.length shouldBe 1
      nodes.length shouldBe 2
      hasStream shouldBe false
    }

    test
  }

  it should "be separated correctly with string children" in {

    val modifiers: Seq[Modifier] = Seq(
      Attribute("class","red"),
      EmptyModifier,
      Emitter("click", _ => Continue),
      Emitter("input",  _ => Continue),
      Emitter("keyup",  _ => Continue),
      AttributeStreamReceiver("hidden",Observable()),
      AttributeStreamReceiver("disabled",Observable()),
      StringModifier("text"),
      StringVNode("text2")
    )

    val SeparatedModifiers(properties, emitters, receivers, Children.StringModifiers(stringMods)) =
      SeparatedModifiers.from(modifiers)

    emitters.emitters.length shouldBe 3
    receivers.length shouldBe 2
    properties.attributes.attrs.length shouldBe 1
    stringMods.map(_.string) should contain theSameElementsAs List(
      "text", "text2"
    )
  }

  it should "be separated correctly with children and properties" in {

    val modifiers = Seq(
      Attribute("class","red"),
      EmptyModifier,
      Emitter("click", _ => Continue),
      Emitter("input", _ => Continue),
      UpdateHook(PublishSubject()),
      AttributeStreamReceiver("hidden",Observable()),
      AttributeStreamReceiver("disabled",Observable()),
      ModifierStreamReceiver(Observable()),
      Emitter("keyup", _ => Continue),
      InsertHook(PublishSubject()),
      PrePatchHook(PublishSubject()),
      PostPatchHook(PublishSubject()),
      StringModifier("text")
    )

    val SeparatedModifiers(properties, emitters, receivers, Children.VNodes(nodes, hasStream)) =
      SeparatedModifiers.from(modifiers)

    emitters.emitters.map(_.eventType) shouldBe List("click", "input", "keyup")
    emitters.emitters.length shouldBe 3
    properties.hooks.insertHooks.length shouldBe 1
    properties.hooks.prePatchHooks.length shouldBe 1
    properties.hooks.updateHooks.length shouldBe 1
    properties.hooks.postPatchHooks.length shouldBe 1
    properties.hooks.destroyHooks.length shouldBe 0
    properties.attributes.attrs.length shouldBe 1
    receivers.length shouldBe 2
    properties.keys.length shouldBe 0
    nodes.length shouldBe 2
    hasStream shouldBe true
  }

  val fixture = new {
    val proxy = hFunction("div", DataObject(js.Dictionary("class" -> "red", "id" -> "msg"), js.Dictionary()), js.Array(
      hFunction("span", DataObject(js.Dictionary(), js.Dictionary()), "Hello")
    ))
  }

  it should "be run once" in {

    val list = new collection.mutable.ArrayBuffer[String]

    val vtree = div(
      IO {
        list += "child1"
        ModifierStreamReceiver(Observable(div()))
      },
      IO {
        list += "child2"
        ModifierStreamReceiver(Observable())
      },
      IO {
        list += "children1"
        ModifierStreamReceiver(Observable())
      },
      IO {
        list += "children2"
        ModifierStreamReceiver(Observable())
      },
      div(
        IO {
          list += "attr1"
          Attribute("attr1", "peter")
        },
        Seq(
          IO {
            list += "attr2"
            Attribute("attr2", "hans")
          }
        )
      )
    )

    val test = for {

      node ← IO {
        val node = document.createElement("div")
        document.body.appendChild(node)
        node
      }

      _ ← IO(list.isEmpty shouldBe true)
      _ ← OutWatch.renderInto(node, vtree)
    } yield {

      list should contain theSameElementsAs List(
        "child1", "child2", "children1", "children2", "attr1", "attr2"
      )

    }

    test
  }

  it should "provide unique key for child nodes if stream is present" in {

    import cats.syntax.apply._

    val d1 = div(id := "1")
    val d2 = div(id := "2")

    (d1, d2).mapN { (div1, div2) ⇒

      val mods = Seq(
        ModifierStreamReceiver(Observable()),
        div1,
        div2
        // div1, div1 //TODO: this should also work, but key is derived from hashCode of VTree (which in this case is equal)
      )

      val modifiers =  SeparatedModifiers.from(mods)
      val Children.VNodes(nodes, hasStream) = modifiers.children

      nodes.length shouldBe 3
      hasStream shouldBe true

      val proxy = modifiers.toSnabbdom("div")
      proxy.key.isDefined shouldBe true

      proxy.children.get.length shouldBe 2

      val key1 = proxy.children.get(0).key
      val key2 = proxy.children.get(1).key

      key1.isDefined shouldBe true
      key2.isDefined shouldBe true
      key1.get should not be key2.get

    }
  }

  it should "keep existing key for child nodes" in {

    div()(IO.pure(Key(5678))).map { div ⇒

      val mods = Seq(
        Key(1234),
        ModifierStreamReceiver(Observable()),
        div
      )

      val modifiers =  SeparatedModifiers.from(mods)
      val Children.VNodes(nodes, hasStream) = modifiers.children

      nodes.length shouldBe 2
      hasStream shouldBe true

      val proxy = modifiers.toSnabbdom("div")
      proxy.key.toOption  shouldBe Some(1234)

      proxy.children.get(0).key.toOption shouldBe Some(5678)

    }

  }

  "VTrees" should "be constructed correctly" in {

    val attributes = List(Attribute("class", "red"), Attribute("id", "msg"))
    val message = "Hello"
    val child = span(message)

    val vproxy = div(IO.pure(attributes.head), IO.pure(attributes(1)), child).map(_.toSnabbdom)
    val proxy  = fixture.proxy

    vproxy.map(JSON.stringify(_) shouldBe JSON.stringify(proxy))
  }

  it should "be correctly created with the HyperscriptHelper" in {

    val attributes = List(Attribute("class", "red"), Attribute("id", "msg"))
    val message = "Hello"
    val child = span(message)

    val vproxy = div(IO.pure(attributes.head), IO.pure(attributes(1)), child).map(_.toSnabbdom)
    val proxy  = fixture.proxy

    vproxy.map(JSON.stringify(_) shouldBe JSON.stringify(proxy))
  }


  it should "run its modifiers once!" in {

    Handler.create[String]().flatMap { stringHandler ⇒

      var ioCounter = 0
      var handlerCounter = 0
      stringHandler { _ =>
        handlerCounter += 1
      }

      val vtree = div(
        div(
          IO {
            ioCounter += 1
            Attribute("hans", "")
          }
        ),
        stringHandler
      )

      val node = document.createElement("div")
      document.body.appendChild(node)

      ioCounter shouldBe 0
      handlerCounter shouldBe 0


      OutWatch.renderInto(node, vtree).map { _ ⇒

        ioCounter shouldBe 1
        handlerCounter shouldBe 0
        stringHandler.onNext("pups")
        ioCounter shouldBe 1
        handlerCounter shouldBe 1

      }

    }

  }

  it should "run its modifiers once in CompositeModifier!" in {

    Handler.create[String]().flatMap { stringHandler ⇒

      var ioCounter = 0
      var handlerCounter = 0
      stringHandler { _ =>
        handlerCounter += 1
      }

      val vtree = div(
        div(Seq(
          IO {
            ioCounter += 1
            Attribute("hans", "")
          }
        )),
        stringHandler
      )

      val node = document.createElement("div")
      document.body.appendChild(node)

      ioCounter shouldBe 0
      handlerCounter shouldBe 0

      OutWatch.renderInto(node, vtree).map { _ ⇒

        ioCounter shouldBe 1
        handlerCounter shouldBe 0
        stringHandler.onNext("pups")
        ioCounter shouldBe 1
        handlerCounter shouldBe 1

      }

    }

  }

  it should "be correctly patched into the DOM" in {

    val id = "msg"
    val cls = "red"
    val attributes = List(Attribute("class", cls), Attribute("id", id))
    val message = "Hello"
    val child = span(message)
    val vtree = div(IO.pure(attributes.head), IO.pure(attributes(1)), child)

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vtree).map { _ ⇒

          val patchedNode = document.getElementById(id)
          patchedNode.childElementCount shouldBe 1
          patchedNode.classList.contains(cls) shouldBe true
          patchedNode.children(0).innerHTML shouldBe message
      }

    }

  }

  it should "be replaced if they contain changeables" in {

    def page(num: Int): VNode = for {
      pageNum ← Handler.create[Int](num)
      node ← div( id := "page",
        num match {
          case 1 ⇒
            div(pageNum)
          case 2 ⇒
            div(pageNum)
        }
      )
    } yield node

    val pageHandler = PublishSubject[Int]

    val vtree = div(
      div(pageHandler.map(page))
    )

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vtree).map { _ ⇒

        pageHandler.onNext(1)

        val domNode = document.getElementById("page")

        domNode.textContent shouldBe "1"

        pageHandler.onNext(2)

        domNode.textContent shouldBe "2"
      }

    }
  }

  "The HTML DSL" should "construct VTrees properly" in {
    import outwatch.dom._

    val vproxy = div(cls := "red", id := "msg", span("Hello")).map(_.toSnabbdom)
    val proxy  = fixture.proxy

    vproxy.map(JSON.stringify(_) shouldBe JSON.stringify(proxy))
  }

  it should "construct VTrees with optional children properly" in {
    import outwatch.dom._

    val vproxy = div(cls := "red", id := "msg", Option(span("Hello")), Option.empty[VDomModifier]).map(_.toSnabbdom)
    val proxy  = fixture.proxy

    vproxy.map(JSON.stringify(_) shouldBe JSON.stringify(proxy))
  }

  it should "construct VTrees with boolean attributes" in {
    import outwatch.dom._

    def boolBuilder(name: String) = new BasicAttrBuilder[Boolean](name, identity)
    def stringBuilder(name: String) = new BasicAttrBuilder[Boolean](name, _.toString)

    val vproxy = div(
      boolBuilder("a"),
      boolBuilder("b") := true,
      boolBuilder("c") := false,
      stringBuilder("d"),
      stringBuilder("e") := true,
      stringBuilder("f") := false
    ).map(_.toSnabbdom)

    val attrs = js.Dictionary[dom.Attr.Value]("a" -> true, "b" -> true, "c" -> false, "d" -> "true", "e" -> "true", "f" -> "false")
    val expected = hFunction("div", DataObject(attrs, js.Dictionary()))

    vproxy.map(JSON.stringify(_) shouldBe JSON.stringify(expected))

  }

  it should "patch into the DOM properly" in {
    import outwatch.dom._

    val message = "Test"
    val vtree = div(cls := "blue", id := "test",
      span(message),
      ul(id := "list",
        li("1"),
        li("2"),
        li("3")
      )
    )

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap {n ⇒

      OutWatch.renderInto(n, vtree).map {_ ⇒

        val patchedNode = document.getElementById("test")

        patchedNode.childElementCount shouldBe 2
        patchedNode.classList.contains("blue") shouldBe true
        patchedNode.children(0).innerHTML shouldBe message
        document.getElementById("list").childElementCount shouldBe 3
      }

    }
  }

  it should "change the value of a textfield" in {

    val messages = PublishSubject[String]
    val vtree = div(
      input(attributes.value <-- messages, id := "input")
    )

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vtree).map { _ ⇒

        val field = document.getElementById("input").asInstanceOf[html.Input]

        field.value shouldBe ""

        val message = "Hello"
        messages.onNext(message)

        field.value shouldBe message

        val message2 = "World"
        messages.onNext(message2)

        field.value shouldBe message2
      }

    }
  }

  it should "render child nodes in correct order" in {

    val messagesA = PublishSubject[String]
    val messagesB = PublishSubject[String]

    val vNode = div(
      span("A"),
      messagesA.map(span(_)),
      span("B"),
      messagesB.map(span(_))
    )

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vNode).map { _ ⇒

        messagesA.onNext("1")
        messagesB.onNext("2")

        n.innerHTML shouldBe "<div><span>A</span><span>1</span><span>B</span><span>2</span></div>"
      }

    }
  }

  it should "render child string-nodes in correct order" in {

    val messagesA = PublishSubject[String]
    val messagesB = PublishSubject[String]
    val vNode = div(
      "A",
      messagesA,
      "B",
      messagesB
    )

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vNode).map { _ ⇒

        n.innerHTML shouldBe "<div>AB</div>"

        messagesA.onNext("1")
        n.innerHTML shouldBe "<div>A1B</div>"

        messagesB.onNext("2")
        n.innerHTML shouldBe "<div>A1B2</div>"
      }

    }
  }

  it should "render child string-nodes in correct order, mixed with children" in {

    val messagesA = PublishSubject[String]
    val messagesB = PublishSubject[String]
    val messagesC = PublishSubject[Seq[VNode]]

    val vNode = div(
      "A",
      messagesA,
      messagesC,
      "B",
      messagesB
    )

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vNode).map { _ ⇒

        n.innerHTML shouldBe "<div>AB</div>"

        messagesA.onNext("1")
        n.innerHTML shouldBe "<div>A1B</div>"

        messagesB.onNext("2")
        n.innerHTML shouldBe "<div>A1B2</div>"

        messagesC.onNext(Seq(div("5"), div("7")))
        n.innerHTML shouldBe "<div>A1<div>5</div><div>7</div>B2</div>"
      }

    }
  }

  it should "update merged nodes children correctly" in {

    val messages = PublishSubject[Seq[VNode]]
    val otherMessages = PublishSubject[Seq[VNode]]
    val vNode = div(messages)(otherMessages)


    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vNode).map { _ ⇒

        otherMessages.onNext(Seq(div("otherMessage")))
        n.children(0).innerHTML shouldBe "<div>otherMessage</div>"

        messages.onNext(Seq(div("message")))
        n.children(0).innerHTML shouldBe "<div>message</div><div>otherMessage</div>"

        otherMessages.onNext(Seq(div("genus")))
        n.children(0).innerHTML shouldBe "<div>message</div><div>genus</div>"
      }

    }
  }

  it should "update merged nodes separate children correctly" in {

    val messages = PublishSubject[String]
    val otherMessages = PublishSubject[String]
    val vNode = div(messages)(otherMessages)

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vNode).map { _ ⇒

        n.children(0).innerHTML shouldBe ""

        otherMessages.onNext("otherMessage")
        n.children(0).innerHTML shouldBe "otherMessage"

        messages.onNext("message")
        n.children(0).innerHTML shouldBe "messageotherMessage"

        otherMessages.onNext("genus")
        n.children(0).innerHTML shouldBe "messagegenus"
      }

    }
  }

  it should "partially render component even if parts not present" in {

    val messagesColor = PublishSubject[String]
    val messagesBgColor = PublishSubject[String]
    val childString = PublishSubject[String]

    val vNode = div( id := "inner",
      color <-- messagesColor,
      backgroundColor <-- messagesBgColor,
      childString
    )

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vNode).map { _ ⇒

        val inner = document.getElementById("inner").asInstanceOf[html.Div]

        inner.innerHTML shouldBe ""
        inner.style.color shouldBe ""
        inner.style.backgroundColor shouldBe ""

        childString.onNext("fish")
        inner.innerHTML shouldBe "fish"
        inner.style.color shouldBe ""
        inner.style.backgroundColor shouldBe ""

        messagesColor.onNext("red")
        inner.innerHTML shouldBe "fish"
        inner.style.color shouldBe "red"
        inner.style.backgroundColor shouldBe ""

        messagesBgColor.onNext("blue")
        inner.innerHTML shouldBe "fish"
        inner.style.color shouldBe "red"
        inner.style.backgroundColor shouldBe "blue"

      }

    }
  }

  it should "partially render component even if parts not present2" in {

    val messagesColor = PublishSubject[String]
    val childString = PublishSubject[String]

    val vNode = div( id := "inner",
      color <-- messagesColor,
      childString
    )

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vNode).map { _ ⇒

        val inner = document.getElementById("inner").asInstanceOf[html.Div]

        inner.innerHTML shouldBe ""
        inner.style.color shouldBe ""

        childString.onNext("fish")
        inner.innerHTML shouldBe "fish"
        inner.style.color shouldBe ""

        messagesColor.onNext("red")
        inner.innerHTML shouldBe "fish"
        inner.style.color shouldBe "red"
      }

    }
  }

  it should "update reused vnodes correctly" in {

    val messages = PublishSubject[String]
    val vNode = div(data.ralf := true, messages)
    val container = div(vNode, vNode)

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, container).map { _ ⇒

        messages.onNext("message")
        n.children(0).children(0).innerHTML shouldBe "message"
        n.children(0).children(1).innerHTML shouldBe "message"

        messages.onNext("bumo")
        n.children(0).children(0).innerHTML shouldBe "bumo"
        n.children(0).children(1).innerHTML shouldBe "bumo"
      }

    }
  }

  it should "update merged nodes correctly (render reuse)" in {

    val messages = PublishSubject[String]
    val otherMessages = PublishSubject[String]
    val vNodeTemplate = div(messages)
    val vNode = vNodeTemplate(otherMessages)

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    val test = for {

      node1 ← node
       _ ← OutWatch.renderInto(node1, vNodeTemplate)

      node2 ← node
       _ ← OutWatch.renderInto(node2, vNode)

    } yield {

      messages.onNext("gurkon")
      otherMessages.onNext("otherMessage")
      node1.children(0).innerHTML shouldBe "gurkon"
      node2.children(0).innerHTML shouldBe "gurkonotherMessage"

      messages.onNext("message")
      node1.children(0).innerHTML shouldBe "message"
      node2.children(0).innerHTML shouldBe "messageotherMessage"

      otherMessages.onNext("genus")
      node1.children(0).innerHTML shouldBe "message"
      node2.children(0).innerHTML shouldBe "messagegenus"
    }

    test
  }

  it should "update merged node attributes correctly" in {

    val messages = PublishSubject[String]
    val otherMessages = PublishSubject[String]
    val vNode = div(data.noise <-- messages)(data.noise <-- otherMessages)

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vNode).map { _ ⇒

        otherMessages.onNext("otherMessage")
        n.children(0).getAttribute("data-noise") shouldBe "otherMessage"

        messages.onNext("message") // should be ignored
        n.children(0).getAttribute("data-noise") shouldBe "otherMessage"

        otherMessages.onNext("genus")
        n.children(0).getAttribute("data-noise") shouldBe "genus"
      }

    }
  }

  it should "update merged node styles written with style() correctly" in {

    val messages = PublishSubject[String]
    val otherMessages = PublishSubject[String]
    val vNode = div(style("color") <-- messages)(style("color") <-- otherMessages)

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vNode).map { _ ⇒

        otherMessages.onNext("red")
        n.children(0).asInstanceOf[html.Element].style.color shouldBe "red"

        messages.onNext("blue") // should be ignored
        n.children(0).asInstanceOf[html.Element].style.color shouldBe "red"

        otherMessages.onNext("green")
        n.children(0).asInstanceOf[html.Element].style.color shouldBe "green"
      }

    }
  }

  it should "update merged node styles correctly" in {

    val messages = PublishSubject[String]
    val otherMessages = PublishSubject[String]
    val vNode = div(color <-- messages)(color <-- otherMessages)

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vNode).map { _ ⇒

        otherMessages.onNext("red")
        n.children(0).asInstanceOf[html.Element].style.color shouldBe "red"

        messages.onNext("blue") // should be ignored
        n.children(0).asInstanceOf[html.Element].style.color shouldBe "red"

        otherMessages.onNext("green")
        n.children(0).asInstanceOf[html.Element].style.color shouldBe "green"
      }

    }
  }


  it should "render composite VNodes properly" in {

    val items = Seq("one", "two", "three")
    val vNode = div(items.map(item => span(item)))

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vNode).map { _ ⇒

        n.innerHTML shouldBe "<div><span>one</span><span>two</span><span>three</span></div>"
      }

    }
  }

  it should "render nodes with only attribute receivers properly" in {

    val classes = PublishSubject[String]
    val vNode = button( className <-- classes, "Submit")

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vNode).map { _ ⇒

        classes.onNext("active")

        n.innerHTML shouldBe """<button class="active">Submit</button>"""
      }

    }
  }

  it should "work with custom tags" in {

    val vNode = div(tag("main")())

    val node = IO {
      val node = document.createElement("div")
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      OutWatch.renderInto(n, vNode).map { _ ⇒

        n.innerHTML shouldBe "<div><main></main></div>"
      }

    }
  }

  it should "work with un-assigned booleans attributes and props" in {

    val vNode = option(selected, disabled)

    val node = IO {
      val node = document.createElement("option").asInstanceOf[html.Option]
      document.body.appendChild(node)
      node
    }

    node.flatMap { n ⇒

      n.selected shouldBe false
      n.disabled shouldBe false

      OutWatch.renderReplace(n, vNode).map {_ ⇒

        n.selected shouldBe true
        n.disabled shouldBe true
      }

    }
  }

  it should "correctly work with AsVDomModifier conversions" in {

    val test: IO[Assertion] = for {
      n1 ← IO(document.createElement("div"))

       _ ← OutWatch.renderReplace(n1, div("one"))
       _ = n1.innerHTML shouldBe "one"

       _ ← OutWatch.renderReplace(n1, div(Some("one")))
       _ = n1.innerHTML shouldBe "one"

      n2 ← IO(document.createElement("div"))

       _ ← OutWatch.renderReplace(n2, div(None:Option[Int]))
       _ = n2.innerHTML shouldBe ""

      _ ← OutWatch.renderReplace(n1, div(1))
      _ = n1.innerHTML shouldBe "1"

      _ ← OutWatch.renderReplace(n1, div(1.0))
      _ = n1.innerHTML shouldBe "1"

      _ ← OutWatch.renderReplace(n1, div(Seq("one", "two")))
      _ = n1.innerHTML shouldBe "onetwo"

      _ ← OutWatch.renderReplace(n1, div(Seq(1, 2)))
      _ = n1.innerHTML shouldBe "12"

      _ ← OutWatch.renderReplace(n1, div(Seq(1.0, 2.0)))
      _ = n1.innerHTML shouldBe "12"

    } yield succeed

    test
  }

  "Children stream" should "work for string sequences" in {

    val myStrings: Observable[Seq[String]] = Observable(Seq("a", "b"))
    val node = div(id := "strings", myStrings)

    OutWatch.renderInto("#app", node).map { _ ⇒

      val element = document.getElementById("strings")
      element.innerHTML shouldBe "ab"

    }
  }

  "Children stream" should "work for double/boolean/long/int" in {

    val node = div(id := "strings",
      1.1, true, 133L, 7
    )

    OutWatch.renderInto("#app", node).map { _ ⇒

      val element = document.getElementById("strings")
      element.innerHTML shouldBe "1.1true1337"

    }
  }

  "Child stream" should "work for string options" in {

    Handler.create(Option("a")).flatMap { myOption ⇒

      val node = div(id := "strings", myOption)

      OutWatch.renderInto("#app", node).map { _ ⇒

        val element = document.getElementById("strings")
        element.innerHTML shouldBe "a"

        myOption.onNext(None)
        element.innerHTML shouldBe ""

      }

    }
  }

  it should "work for vnode options" in {

    Handler.create(Option(div("a"))).flatMap { myOption ⇒

      val node = div(id := "strings", myOption)

      OutWatch.renderInto("#app", node).map { _ ⇒

        val element = document.getElementById("strings")
        element.innerHTML shouldBe "<div>a</div>"

        myOption.onNext(None)
        element.innerHTML shouldBe ""

      }

    }
  }

  "Modifier stream" should "work for modifier" in {

    Handler.create[VDomModifier](Seq(cls := "hans", b("stark"))).flatMap { myHandler ⇒

      val node = div(id := "strings",
        div(IO.pure(ModifierStreamReceiver(myHandler)))
      )

      OutWatch.renderInto("#app", node).map { _ ⇒

        val element = document.getElementById("strings")
        element.innerHTML shouldBe """<div class="hans"><b>stark</b></div>"""

        myHandler.onNext(Option(id := "fair"))
        element.innerHTML shouldBe """<div id="fair"></div>"""

      }

    }
  }

  it should "work for multiple mods" in {

    val test: IO[Assertion] = Handler.create[VDomModifier]().flatMap { myHandler ⇒

      val node = div(id := "strings",
        div(IO.pure(ModifierStreamReceiver(myHandler)), "bla")
      )

      OutWatch.renderInto("#app", node).flatMap { _ ⇒

        val element = document.getElementById("strings")
        element.innerHTML shouldBe "<div>bla</div>"

        myHandler.onNext(cls := "hans")
        element.innerHTML shouldBe """<div class="hans">bla</div>"""

        Handler.create[VDomModifier]().map { innerHandler ⇒

          myHandler.onNext(div(
            IO.pure(ModifierStreamReceiver(innerHandler)),
            cls := "no?",
            "yes?"
          ))
          element.innerHTML shouldBe """<div><div class="no?">yes?</div>bla</div>"""

          innerHandler.onNext(Seq(span("question:"), id := "heidi"))
          element.innerHTML shouldBe """<div><div class="no?" id="heidi"><span>question:</span>yes?</div>bla</div>"""

          myHandler.onNext(div(
            IO.pure(ModifierStreamReceiver(innerHandler)),
            cls := "no?",
            "yes?",
            b("go!")
          ))

          element.innerHTML shouldBe """<div><div class="no?">yes?<b>go!</b></div>bla</div>"""

          innerHandler.onNext(Seq(span("question and answer:"), id := "heidi"))
          element.innerHTML shouldBe """<div><div class="no?" id="heidi"><span>question and answer:</span>yes?<b>go!</b></div>bla</div>"""

          myHandler.onNext(Seq(span("nope")))
          element.innerHTML shouldBe """<div><span>nope</span>bla</div>"""

          innerHandler.onNext(b("me?"))
          element.innerHTML shouldBe """<div><span>nope</span>bla</div>"""

        }
      }

    }

    test
  }

  it should "work for nested modifier stream receiver" in {

    Handler.create[VDomModifier]().flatMap { myHandler ⇒

      val node = div(id := "strings",
        div(IO.pure(ModifierStreamReceiver(myHandler)))
      )

      OutWatch.renderInto("#app", node).flatMap { _ ⇒

        val element = document.getElementById("strings")
        element.innerHTML shouldBe "<div></div>"

        Handler.create[VDomModifier]().flatMap { innerHandler ⇒

          myHandler.onNext(IO.pure(ModifierStreamReceiver(innerHandler)))
          element.innerHTML shouldBe """<div></div>"""

          innerHandler.onNext(VDomModifier(cls := "hans", "1"))
          element.innerHTML shouldBe """<div class="hans">1</div>"""

          Handler.create[VDomModifier]().map { innerHandler2 ⇒

            myHandler.onNext(IO.pure(ModifierStreamReceiver(innerHandler2)))
            element.innerHTML shouldBe """<div></div>"""

            myHandler.onNext(IO.pure(CompositeModifier(ModifierStreamReceiver(innerHandler2) :: Nil)))
            element.innerHTML shouldBe """<div></div>"""

            myHandler.onNext(IO.pure(CompositeModifier(StringModifier("pete") :: ModifierStreamReceiver(innerHandler2) :: Nil)))
            element.innerHTML shouldBe """<div>pete</div>"""

            innerHandler2.onNext(VDomModifier(id := "dieter", "r"))
            element.innerHTML shouldBe """<div id="dieter">peter</div>"""

            innerHandler.onNext(b("me?"))
            element.innerHTML shouldBe """<div id="dieter">peter</div>"""

            myHandler.onNext(span("the end"))
            element.innerHTML shouldBe """<div><span>the end</span></div>"""

          }
        }
      }

    }
  }

  it should "work for nested observables with seq modifiers " in {

    Handler.create("b").flatMap { innerHandler ⇒
    Handler.create(Seq[VDomModifier]("a", data.test := "v", innerHandler)).flatMap { outerHandler ⇒

      val node = div(
        id := "strings",
        outerHandler
      )

      OutWatch.renderInto("#app", node).map { _ ⇒

        val element = document.getElementById("strings")
        element.outerHTML shouldBe """<div id="strings" data-test="v">ab</div>"""

        innerHandler.onNext("c")
        element.outerHTML shouldBe """<div id="strings" data-test="v">ac</div>"""

        outerHandler.onNext(Seq[VDomModifier]("meh"))
        element.outerHTML shouldBe """<div id="strings">meh</div>"""
      }

    }}
  }

  it should "work for nested observables with seq modifiers and attribute stream" in {

    Handler.create[String]().flatMap { innerHandler ⇒
    Handler.create(Seq[VDomModifier]("a", data.test := "v", href <-- innerHandler)).flatMap { outerHandler ⇒

      val node = div(
        id := "strings",
        outerHandler
      )

      OutWatch.renderInto("#app", node).map { _ ⇒

        val element = document.getElementById("strings")
        element.outerHTML shouldBe """<div id="strings" data-test="v">a</div>"""

        innerHandler.onNext("c")
        element.outerHTML shouldBe """<div id="strings" data-test="v" href="c">a</div>"""

        innerHandler.onNext("d")
        element.outerHTML shouldBe """<div id="strings" data-test="v" href="d">a</div>"""

        outerHandler.onNext(Seq[VDomModifier]("meh"))
        element.outerHTML shouldBe """<div id="strings">meh</div>"""
      }

    }}
  }

  it should "work for double nested modifier stream receiver" in {

    Handler.create[VDomModifier]().flatMap { myHandler ⇒

      val node = div(id := "strings",
        div(IO.pure(ModifierStreamReceiver(myHandler)))
      )

      OutWatch.renderInto("#app", node).map { _ ⇒

        val element = document.getElementById("strings")
        element.innerHTML shouldBe "<div></div>"

        myHandler.onNext(IO.pure(ModifierStreamReceiver(Observable[VDomModifier](IO.pure(ModifierStreamReceiver(Observable[VDomModifier](cls := "hans")))))))
        element.innerHTML shouldBe """<div class="hans"></div>"""
      }

    }
  }

  it should "work for triple nested modifier stream receiver" in {

    Handler.create[VDomModifier]().flatMap { myHandler ⇒

      val node = div(id := "strings",
        div(IO.pure(ModifierStreamReceiver(myHandler)))
      )

      OutWatch.renderInto("#app", node).map { _ ⇒

        val element = document.getElementById("strings")
        element.innerHTML shouldBe "<div></div>"

        myHandler.onNext(IO.pure(ModifierStreamReceiver(Observable[VDomModifier](IO.pure(ModifierStreamReceiver(Observable[VDomModifier](IO.pure(ModifierStreamReceiver(Observable(cls := "hans"))))))))))
        element.innerHTML shouldBe """<div class="hans"></div>"""
      }

    }
  }

  it should "work for multiple nested modifier stream receiver" in {

    Handler.create[VDomModifier]().flatMap { myHandler ⇒

      val node = div(id := "strings",
        div(IO.pure(ModifierStreamReceiver(myHandler)))
      )

      OutWatch.renderInto("#app", node).map { _ ⇒

        val element = document.getElementById("strings")
        element.innerHTML shouldBe "<div></div>"

        myHandler.onNext(IO.pure(ModifierStreamReceiver(Observable[VDomModifier](VDomModifier(IO.pure(ModifierStreamReceiver(Observable[VDomModifier]("a"))), IO.pure(ModifierStreamReceiver(Observable(span("b")))))))))
        element.innerHTML shouldBe """<div>a<span>b</span></div>"""
      }

    }
  }

  it should "work for nested attribute stream receiver" in {

    Handler.create[VDomModifier]().flatMap { myHandler ⇒

      val node = div(id := "strings",
        div(IO.pure(ModifierStreamReceiver(myHandler)))
      )

      OutWatch.renderInto("#app", node).map { _ ⇒

        val element = document.getElementById("strings")
        element.innerHTML shouldBe "<div></div>"

        myHandler.onNext(cls <-- Observable("hans"))
        element.innerHTML shouldBe """<div class="hans"></div>"""
      }

    }
  }

  it should "work for nested emitter" in {

    Handler.create[VDomModifier]().flatMap { myHandler ⇒

      val node = div(id := "strings",
        div(id := "click", IO.pure(ModifierStreamReceiver(myHandler)))
      )

      OutWatch.renderInto("#app", node).map { _ ⇒

        val element = document.getElementById("strings")
        element.innerHTML shouldBe """<div id="click"></div>"""

        var clickCounter = 0
        myHandler.onNext(onClick --> sideEffect(_ => clickCounter += 1))
        element.innerHTML shouldBe """<div id="click"></div>"""

        clickCounter shouldBe 0
        val event = document.createEvent("Events")
        initEvent(event)("click", canBubbleArg = true, cancelableArg = false)
        document.getElementById("click").dispatchEvent(event)
        clickCounter shouldBe 1
      }

    }
  }

  it should "work for streaming accum attributes" in {

    Handler.create[String]("second").flatMap { myClasses ⇒
    Handler.create[String]().flatMap { myClasses2 ⇒

      val node = div(
        id := "strings",
        div(
          cls := "first",
          myClasses.map { cls := _ },
          Seq[VDomModifier](
            cls <-- myClasses2
          )
        )
      )

      OutWatch.renderInto("#app", node).map {_ ⇒
        val element = document.getElementById("strings")

        element.innerHTML shouldBe """<div class="first second"></div>"""

        myClasses2.onNext("third")
        element.innerHTML shouldBe """<div class="first second third"></div>"""

        myClasses2.onNext("more")
        element.innerHTML shouldBe """<div class="first second more"></div>"""

        myClasses.onNext("yeah")
        element.innerHTML shouldBe """<div class="first yeah more"></div>"""
      }

    }}
  }

  "LocalStorage" should "provide a handler" in {

    val key = "banana"
    val triggeredHandlerEvents = mutable.ArrayBuffer.empty[Option[String]]

    assert(localStorage.getItem(key) == null)

    util.LocalStorage.handler(key).flatMap { storageHandler ⇒

      storageHandler.foreach{e => triggeredHandlerEvents += e}
      assert(localStorage.getItem(key) == null)
      assert(triggeredHandlerEvents.toList == List(None))

      storageHandler.onNext(Some("joe"))
      assert(localStorage.getItem(key) == "joe")
      assert(triggeredHandlerEvents.toList == List(None, Some("joe")))

      var initialValue:Option[String] = null

      util.LocalStorage.handler(key).map { sh ⇒

        sh.foreach {initialValue = _}
        assert(initialValue == Some("joe"))

        storageHandler.onNext(None)
        assert(localStorage.getItem(key) == null)
        assert(triggeredHandlerEvents.toList == List(None, Some("joe"), None))

        // localStorage.setItem(key, "split") from another window
        dispatchStorageEvent(key, newValue = "split", null)
        assert(localStorage.getItem(key) == "split")
        assert(triggeredHandlerEvents.toList == List(None, Some("joe"), None, Some("split")))

        // localStorage.removeItem(key) from another window
        dispatchStorageEvent(key, null, "split")
        assert(localStorage.getItem(key) == null)
        assert(triggeredHandlerEvents.toList == List(None, Some("joe"), None, Some("split"), None))

        // only trigger handler if value changed
        storageHandler.onNext(None)
        assert(localStorage.getItem(key) == null)
        assert(triggeredHandlerEvents.toList == List(None, Some("joe"), None, Some("split"), None))

        storageHandler.onNext(Some("rhabarbar"))
        assert(localStorage.getItem(key) == "rhabarbar")
        assert(triggeredHandlerEvents.toList == List(None, Some("joe"), None, Some("split"), None, Some("rhabarbar")))

        // localStorage.clear() from another window
        dispatchStorageEvent(null, null, null)
        assert(localStorage.getItem(key) == null)
        assert(triggeredHandlerEvents.toList == List(None, Some("joe"), None, Some("split"), None, Some("rhabarbar"), None))
      }
    }

  }

}
