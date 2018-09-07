package outwatch

import scala.scalajs.js
import org.scalajs.dom.{document, html}
import outwatch.Deprecated.IgnoreWarnings.initEvent
import outwatch.dom.OutWatch
import outwatch.dom.dsl._
import cats.effect.IO
import snabbdom.{DataObject, hFunction, patch}

class SnabbdomSpec extends JSDomAsyncSpec {

  // TODO: redo this using regular flatmap

  "The Snabbdom Facade" should "correctly patch the DOM" in {

    val message = "Hello World"

    for {

       vNode ← IO(hFunction("span#msg", DataObject(js.Dictionary(), js.Dictionary()), message))

        node ← IO {
                val node = document.createElement("div")
                document.body.appendChild(node)
                node
              }

            _ ← IO(patch(node, vNode))
          msg ← IO(document.getElementById("msg").innerHTML)
            _ ← IO(msg shouldBe message)
       newMsg = "Hello Snabbdom!"
      newNode ← IO(hFunction("div#new", DataObject(js.Dictionary(), js.Dictionary()), newMsg))
            _ ← IO(patch(vNode, newNode))
         nMsg ← IO(document.getElementById("new").innerHTML)
            _ ← IO(nMsg shouldBe newMsg)

    } yield succeed

  }

  it should "correctly patch nodes with keys" in {

    def inputElement() = document.getElementById("input").asInstanceOf[html.Input]

    Handler.create[Int](1).flatMap { clicks ⇒

      val nodes = clicks.map { i =>
        div(
          attributes.key := s"key-$i",
          span(onClick(if (i == 1) 2 else 1) --> clicks, s"This is number $i", id := "btn"),
          input(id := "input")
        )
      }

      for {

               _ ← IO {
                   val node = document.createElement("div")
                   node.id = "app"
                   document.body.appendChild(node)
                   node
                 }

               _ ← OutWatch.renderInto("#app", div(nodes))

        inputEvt ← IO {
                    val inputEvt = document.createEvent("HTMLEvents")
                    initEvent(inputEvt)("input", canBubbleArg = false, cancelableArg = true)
                    inputEvt
                  }

        clickEvt ← IO {
                    val clickEvt = document.createEvent("Events")
                    initEvent(clickEvt)("click", canBubbleArg = true, cancelableArg = true)
                    clickEvt
                  }

             btn ← IO(document.getElementById("btn"))

              ie ← IO {
                    inputElement().value = "Something"
                    inputElement().dispatchEvent(inputEvt)
                    btn.dispatchEvent(clickEvt)
                    inputElement().value
                  }
               _ ← IO(ie shouldBe "")

      } yield succeed

    }
  }

  it should "correctly handle boolean attributes" in {

    val message    = "Hello World"
    val attributes = js.Dictionary[dom.Attr.Value]("bool1" -> true, "bool0" -> false, "string1" -> "true", "string0" -> "false")
    val expected   = s"""<span id="msg" bool1="" string1="true" string0="false">$message</span>"""

    for {

      vNode ← IO(hFunction("span#msg", DataObject(attributes, js.Dictionary()), message))
       node ← IO {
                val node = document.createElement("div")
                document.body.appendChild(node)
                node
              }
          _ ← IO(patch(node, vNode))
       html ← IO(document.getElementById("msg").outerHTML)
          _ ← IO(html shouldBe expected)

    } yield succeed

  }
}
