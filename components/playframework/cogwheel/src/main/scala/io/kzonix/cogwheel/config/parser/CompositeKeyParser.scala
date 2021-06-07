package io.kzonix.cogwheel.config.parser

import com.typesafe.config._
import io.kzonix.cogwheel.config.parser.CompositeKeyParser.Node
import io.kzonix.cogwheel.config.parser.CompositeKeyParser.ParamNamePatters.PathWithSequenceIndex
import io.kzonix.cogwheel.config.parser.CompositeKeyParser.ParamNamePatters.SequenceIndex

import scala.collection.immutable
import scala.jdk.CollectionConverters._

class CompositeKeyParser(nextParser: ParameterParser[Map[String, ConfigValue], ConfigValue])
    extends ParameterParser[Map[String, ConfigValue], ConfigValue] {

  private final def buildTree(node: Node): ConfigValue = {

    // check if the current recursion call reaches the end for some specific child entry
    // if it reach the end of one invocation branch, we need to filter them out from the list of child eligible for further recursion calls.
    //   "/region-1/customer/another_app/1/nested1/1/nested2/3/nested3 | "     -> json, <-- end, should be marked as finished node
    //   "/region-1/customer/another_app/1/nested1/1/nested2/3/nested3 | /last" -> json  <-- should be recursively processed and merged on step back with previously finished nodes
    // [step 1]
    val finishedEntries: Map[String, ConfigValue]       = node.children
      .filter { case (key, _) => key.isEmpty }
    val finishedValues: immutable.Iterable[ConfigValue] = finishedEntries.map { case (_, value) => value }
    // prepare all non-finished entries for next recursion calls
    val interimNodes: Seq[Node]                         = (node.children -- finishedEntries.keySet)
      .groupMap {
        case (key, _) => extractKeyParts(key).head
      } {
        case (key, value) => extractKeyParts(key).tail.mkString("/") -> value
      }
      .map {
        case (key, value) =>
          if (key.matches(SequenceIndex))
            node.markCollection() // dirty hack to mark parent node as the one that holds collection of elements
          Node(
            key,
            value.toMap
          )
      }
      .toSeq

    // if filtered collection of child nodes (for further recursive calls) is empty
    // then it means the the single element is left in the tail

    // e.g "/region-1/customer/another_app/1/nested1/1/nested2/3/nested3"
    // e.g "/region-1/customer/another_app/1/nested1/1/nested2/3/nested3/last" <-- we cannot have this possible coz of uniqueness of param-names in SSM
    //     "/region-1/customer/another_app/1/nested1/1/nested2/3/nested3/last" <--
    if (interimNodes.isEmpty) node.children.values.head
    else {
      // otherwise we have go down in our recursive data structure on each child element
      val builtNodes: Seq[ConfigValue] = interimNodes.map { node =>
         val configValue = buildTree(node)
         // construct config object for nested objects
         // e.g: "/region-1/customer/another_app/1/nested1/1/nested2/3/nested3/aaa" -> value
         //      "/region-1/customer/another_app/1/nested1/1/nested2/3 -> { "nested3": { "last": { ..value } } }
         if (!node.id.matches(SequenceIndex))
           configValue.atPath(node.id).root()
         else
           // otherwise config object should not be wrapped with parent key
           // e.g:
           //      "/region-1/customer/another_app/1/nested1/1/nested2/0 -> { "nested3": { "last": { ..value } } }
           //      "/region-1/customer/another_app/1/nested1/1/nested2/1 -> { "nested3": { "last": { ..value } } }
           //                                                          ^ if parent is collection identifier
           configValue
      }
      // Combine all folded child nodes to 'ConfigList' type
      if (node.isCollection) ConfigValueFactory.fromIterable(builtNodes.asJava)
      // If there is some 'finishedValues', then it means that current node can not be a collection holder
      // Combine finished nodes with those that are built on this step.
      else if (finishedValues.size == 1)
        finishedValues.head
          .withFallback(
            builtNodes.foldLeft(ConfigFactory.empty().root()) { case (c1, c2) => c1.withFallback(c2) }
          )
      else
        // if current current node is not be collection holder, all 'built' nodes should merged as the object type 'ConfigObject'
        builtNodes.foldLeft(ConfigFactory.empty().root()) { case (c1, c2) => c1.withFallback(c2) }
    }
  }

  private def extractKeyParts(key: String) =
    key.split("/").filter(_.nonEmpty)

  def parse(parameters: Map[String, ConfigValue]): ConfigObject = {
    val (compositeParams, simpleParams) = parameters.partition {
      case (path, _) => PathWithSequenceIndex.r.findAllIn(path).nonEmpty
    }
    val cfg                             = if (compositeParams.nonEmpty) {
      val config = buildTree(
        Node(
          "root", // dummy value, will ignored
          compositeParams
        )
      )
      // convert config value to object at some key level wrapping to enclosed key and fetching config object by the same key
      config.atKey("root").getConfig("root")
    } else ConfigFactory.empty()

    // combines the result with the result of other parser
    cfg.root().withFallback(nextParser.parse(simpleParams))
  }

}

object CompositeKeyParser {

  case class Node(id: String, children: Map[String, ConfigValue]) {
    var isCollection: Boolean  = false
    def markCollection(): Unit = this.isCollection = true
  }

  object ParamNamePatters {
    val SequenceIndex         = "[0-9]+"
    val PathWithSequenceIndex = s"/$SequenceIndex(/)?"

  }

}
