package edu.cmu.cs.oak.lib.builtin

import java.nio.file.Path

import com.caucho.quercus.expr.Expr
import edu.cmu.cs.oak.core.OakInterpreter
import edu.cmu.cs.oak.value.OakValue
import edu.cmu.cs.oak.value.IntValue
import edu.cmu.cs.oak.lib.InterpreterPlugin
import edu.cmu.cs.oak.lib.InterpreterPluginProvider
import edu.cmu.cs.oak.env.Environment
import edu.cmu.cs.oak.nodes.DNode
import edu.cmu.cs.oak.value.StringValue
import edu.cmu.cs.oak.core.SymbolFlag
import edu.cmu.cs.oak.value.SymbolValue
import edu.cmu.cs.oak.env.OakHeap
import java.util.regex.PatternSyntaxException
import com.caucho.quercus.Location
import edu.cmu.cs.oak.core.OakInterpreter
import edu.cmu.cs.oak.value.OakValue
import edu.cmu.cs.oak.lib.InterpreterPlugin
import edu.cmu.cs.oak.lib.InterpreterPluginProvider
import edu.cmu.cs.oak.env.Environment
import edu.cmu.cs.oak.core.OakInterpreter
import edu.cmu.cs.oak.value.OakValue
import edu.cmu.cs.oak.lib.InterpreterPlugin
import edu.cmu.cs.oak.lib.InterpreterPluginProvider
import edu.cmu.cs.oak.env.Environment
import edu.cmu.cs.oak.core.OakInterpreter
import edu.cmu.cs.oak.value.OakValue
import edu.cmu.cs.oak.lib.InterpreterPlugin
import edu.cmu.cs.oak.lib.InterpreterPluginProvider
import edu.cmu.cs.oak.env.Environment
import edu.cmu.cs.oak.core.OakInterpreter
import edu.cmu.cs.oak.value.OakValue
import edu.cmu.cs.oak.lib.InterpreterPlugin
import edu.cmu.cs.oak.lib.InterpreterPluginProvider
import edu.cmu.cs.oak.value.ObjectValue
import edu.cmu.cs.oak.value.BooleanValue
import edu.cmu.cs.oak.value.BooleanValue
import edu.cmu.cs.oak.exceptions.VariableNotFoundException

class GetClass extends InterpreterPlugin {

  override def getName(): String = "get_class"

  override def visit(provider: InterpreterPluginProvider, args: List[OakValue], loc: Location, env: Environment): OakValue = {

    val interpreter = provider.asInstanceOf[OakInterpreter]

    /* Assert that the function has been o*/
    assert(args.size > 1)

    args.head match {
      case ov: ObjectValue => {
        return StringValue(ov.getClassDef().toString, "", 0)
      }
      case _ => BooleanValue(false)
    }
  }

}