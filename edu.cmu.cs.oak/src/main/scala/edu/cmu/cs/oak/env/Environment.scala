package edu.cmu.cs.oak.env

import scala.collection.immutable.Stack
import scala.collection.mutable.AnyRefMap
import scala.collection.mutable.HashSet
import scala.collection.mutable.ListBuffer
import scala.xml.PrettyPrinter

import org.slf4j.LoggerFactory

import com.caucho.quercus.expr.Expr
import com.caucho.quercus.function.AbstractFunction
import com.caucho.quercus.program.Arg
import com.caucho.quercus.program.Function
import com.caucho.quercus.statement.Statement

import edu.cmu.cs.oak.exceptions.VariableNotFoundException
import edu.cmu.cs.oak.nodes.ConcatNode
import edu.cmu.cs.oak.nodes.ConcatNode
import edu.cmu.cs.oak.nodes.ConcatNode
import edu.cmu.cs.oak.nodes.ConcatNode
import edu.cmu.cs.oak.nodes.ConcatNode
import edu.cmu.cs.oak.nodes.DNode
import edu.cmu.cs.oak.value.ArrayValue
import edu.cmu.cs.oak.value.Choice

import edu.cmu.cs.oak.value.OakValue
import edu.cmu.cs.oak.value.OakValue
import edu.cmu.cs.oak.value.OakValueSequence
import edu.cmu.cs.oak.value.ObjectValue
import edu.cmu.cs.oak.value.Reference
import edu.cmu.cs.oak.value.StringValue
import edu.cmu.cs.oak.value.SymbolValue
import edu.cmu.cs.oak.value.MapChoice
import edu.cmu.cs.oak.nodes.ConcatNode
import edu.cmu.cs.oak.value.ArrayValue
import edu.cmu.cs.oak.value.ArrayValue
import edu.cmu.cs.oak.nodes.ConcatNode
import edu.cmu.cs.oak.value.NullValue
import edu.cmu.cs.oak.core.ArrayUpdateRecordMap


/**
 * Programs state and program state operations.
 *
 * @author Stefan Muehlbauer <smuhlbau@andrew.cmu.edu>
 */
class Environment(parent: Environment, calls: Stack[Call], constraint: Constraint) extends EnvListener {

  var age = 0

  var changed = false

  var symbolic = true
  var single_branch = false
  
  // Keep track of touched string literals
  val touched = new collection.mutable.HashSet[StringValue]()
  
  // Keep track of all include expressions
  val include_history = new collection.mutable.HashMap[(String, Int), Boolean]

  // standard lib functions
  val unknown_standard_functions = new collection.mutable.HashMap[String, Int]()
  
  /**
   * Map of variable identifiers and variable references.
   * In various contexts, variables can refer to the same value.
   * For variable reference handling {@see {@link #edu.cmu.cs.oak.env.Oa kHeap}}.
   */
  val variables = AnyRefMap[String, OakValue]()

  /**
   * Changes in the static class fields
   */
  val staticClassFields = collection.mutable.Map[String, collection.mutable.Map[String, OakValue]]()

  /**
   * Map of references
   */
  val references = AnyRefMap[Reference, OakValue]()

  /**
   * Map of constants that are defined during the execution
   *  of a PHP script.
   */
  val constants = AnyRefMap[String, OakValue]()

  /**
   * Map of global variable identifiers and variable references.
   */
  val globalVariables = HashSet[String]()

  //var loopModeEnabled = false;

  /**
   * Output (D Model) of the environment.
   */
  val output = ConcatNode(List())

  /**
   * Map of fuńction names and function definitions
   */
  var funcs = AnyRefMap[String, FunctionDef]()

  /**
   * Map of class definitions. All classes defined during the program execution
   * are stored here.
   */
  var classDefs = AnyRefMap[String, ClassDef]()

  /**
   * Environment flag to be set to TRUE, once a return statement has
   * been executed. This flag will be checked when a statement/block
   * is executed.
   */
  private var terminated = false
  
  val array_updates = new ArrayUpdateRecordMap()
  

  val logger = LoggerFactory.getLogger(classOf[Environment])

  /**
   * Updates a variable in the environment, i.e. it stores a variable
   * assignment, such as '$i = 8'.
   * @paran name Name of the variable
   * @param value OakValue to assign to the variable
   */
  def update(name: String, value: OakValue) {
    changed = true
    if (variables.contains(name)) {
      val ref = variables.get(name).get.asInstanceOf[Reference]
      references.put(ref, value)
    } else {
      val variable = Reference(name + OakHeap.getIndex(), name)
      references.put(variable, value)
      variables.put(name, variable)
    }
  }

  def isSymbolic() = symbolic

  def toConcreteBranch() {
    symbolic = false
    this.toSingleBranch()
  }

  def isSingleBranch() = single_branch

  def toSingleBranch() {
    single_branch = true
  }

  def isFunctionEnv(): Boolean = (this.parent != null) && (this.parent.getCalls().size < getCalls().size)

  def hasChanged = changed

  def isGlobalVariable(varname: String): Boolean = {
    if (globalVariables contains varname) {
      return true
    } else if (parent != null && (!(parent eq this))) {
      return parent.isGlobalVariable(varname)
    } else {
      return false
    }
  }
  /**
   * Looks up an variable and returns its context-dependent value.
   * @param name Name of the variable
   * @return value Value of the variable
   */
  def lookup(name: String): OakValue = {
    var reference = if (isGlobalVariable(name)) {
      getRef(name, false)
    } else {
      try {
        getRef(name)
      } catch {
        case vnfe: VariableNotFoundException => {
          return NullValue
        }

      }
    }

    def recursiveLookup(reference: Reference): OakValue = {
      this.extract(reference) match {
        case ref2: Reference => recursiveLookup(ref2)
        case null => null
        case ov: OakValue => ov
      }
    }

    val value = try {
      if (reference != null) {
        recursiveLookup(reference)
      } else {
        NullValue
      }
    } catch {
      case vnfe: VariableNotFoundException => throw new RuntimeException(vnfe)
    }
    value
  }

  /**
   * Add output to environment.
   * @param DNode Value to add to output
   */
  def addOutput(o: DNode) {
    changed = true
    output.addOutput(o)
  }

  /**
   * Get the output sequence from the environment.
   * @return output as sequence of values
   */
  def getOutput(): DNode = output

  /**
   * pretty-printed XML
   */
  def getOutputAsPrettyXML(): String = {

    val wrapXML = {
      <DataModel>
        { this.output.toXml() }
      </DataModel>
    }

    var out = (new PrettyPrinter(160, 2)).format(wrapXML)
    out = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n" + out
    out
  }

  /**
   * Manipulation of references via the environment
   *  @param name of the variable
   *  @param reference to point to
   */
  def setRef(name: String, ref: Reference): Unit = {
    changed = true
    variables.put(name, ref)
  }

  def extract(reference: Reference): OakValue = {
    if (references.contains(reference)) {
      references.get(reference).get
    } else if ((parent != null)) {
      parent.extract(reference)
    } else {
      throw new VariableNotFoundException(s"Reference ${reference} not found.")
    }
  }

  def insert(reference: Reference, value: OakValue) {
    changed = true
    references.put(reference, value)
  }

  /**
   * Get the current reference of a variable at runtime.
   * @param name Name of the variable
   * @return reference that variable 'name points to
   */
  def getRef(name: String, limitScope: Boolean = true): Reference = {
    if (variables.contains(name)) {
      val ref = variables.get(name).get.asInstanceOf[Reference]
      ref
    } else if (parent != null) {
      parent.getRef(name)
    } else {
      throw new VariableNotFoundException("Unassigned variable " + name + ".")
    }

  }

  /**
   * Returns the current call stack at runtime.
   * @return Stack of strings where each string denotes a function call
   */
  def getCalls(): Stack[Call] = calls

  /**
   * Returns the environment's parent environment (null if top-level env)
   * @param parent environment
   */
  def getParent(): Environment = parent

  /**
   * Returns the environments path condition
   * @return path condition
   */
  def getConstraint(): Constraint = constraint

  /**
   * Returns the environments map of variable names to references
   * @return map of variable names to references
   */

  def unsetArrayElement(name: String, index: OakValue) {
    val arrayValueO = lookup(name)
    assert(arrayValueO.isInstanceOf[ArrayValue])
    val arrayValue = arrayValueO.asInstanceOf[ArrayValue]
    //heap.unsetVariable(arrayValue.getRef(index))
    arrayValue.set(index, NullValue, this)
  }

  def ifdefy(node: OakValue): List[String] = {
    var res = List[String]()
    node match {
      case seq: OakValueSequence => {
        seq.getSequence.foreach { v => res = res ++ ifdefy(v) }
      }
      case ite: MapChoice => {
        res = res ++ List("# MapChoice ")
      }
      case s: SymbolValue => res = res.::(s.toString())
      case null => res
      case _ => res = res.::(node.toString())
    }
    res
  }

  def unset(name: String) {
    variables.remove(name)
  }

  def ifdefy(): List[String] = output.ifdefy()

  def getParents(): List[Environment] = {
    val parents = new ListBuffer[Environment]
    var cParent = this.parent
    while (cParent != null) {
      parents += cParent
      cParent = cParent.getParent()
    }
    parents.toList
  }

  def addToGlobal(name: String) {
    changed = true

    // Add name to the list of global variables
    this.globalVariables += name

    // get a local copy
    try {
      val v = this.extract(this.getRef(name, false))
      v match {
        case av: ArrayValue => {
          this.update(name, av.deepCopy(this)) // FIXME maybe?
        }
        case ov: OakValue => {
          this.update(name, ov)
        }
        case null => {}
      }
    } catch {
      case vnfe: VariableNotFoundException => {}
    }
  }

  /**
   * Defines a function. The defined function will be accessible during the
   * program execution.
   *
   * @param fu Function instance retrieved from the QuercusProgram to execute
   *
   * @return FunctionDef instance to be stored by the Intepreter
   */
  def defineFunction(fu: AbstractFunction): FunctionDef = {

    val f = fu.asInstanceOf[Function]
    val s = f._name

    val hasReturn = f._hasReturn //Interpreter.accessField(f, "_hasReturn").asInstanceOf[Boolean]
    val returnsRef = f._isReturnsReference //Interpreter.accessField(f, "_isReturnsReference").asInstanceOf[Boolean]
    val args = ListBuffer[String]()
    var defaults = Map[String, Expr]()
    f._args.foreach {
      a =>
        {
          val default = a._default.asInstanceOf[Expr]
          if (default != null) defaults += (a.getName.toString() -> default)
          args.append((if (a.isReference()) "&" else "") + a.getName.toString())
        }
    }
    val statement = f._statement.asInstanceOf[Statement]
    // Add function to the global environment
    return new FunctionDef(f.getName, args.toArray, defaults, statement, hasReturn, returnsRef)
  }

  /**
   * Takes an environment Delta instance and merges with the existing environment. This method
   * is either used for merging different branches (if-else, switch-case) or for merging sub-environments
   * such as function/method calls and loops that are nested in a separate environment. 
   * Merged properties are:
   * 
   * 	1) Output (D Model)
   * 	2) References
   * 	3) Variables
   * 	4) Global variables
   * 	5) Static class fields
   * 	6) Constant definitions
   * 	7) Function definitions (not conditional)
   *  8) Class definitions (not conditional) 
   *  
   *  9) [optional] Touched String literals
   *  10) [optional] Include expressions
   *  11) [optional] Standard lib functions
   * 
   * @param joinResult Delta of an environment instance to be merged
   * 
   */
  def weaveDelta(joinResult: Delta) {

    // 1) Merge output
    if (!joinResult.joinedOutput.isEmpty()) {
      this.addOutput(joinResult.joinedOutput)
    }

    // 2) Update references
    joinResult.joinedHeap.foreach { 
      case (reference, value) => this.insert(reference, value)
    }

    // 3) Update ONLY the variables that have changed during the execution of the branches
    joinResult.joinedVariables.foreach {
      case (name, value) => {
        if (name != null && (name equals "$return")) {
          if (this.isFunctionEnv()) {
            if (!value.isInstanceOf[Reference]) {
              this.update(name, value)
            } else {
              this.setRef(name, value.asInstanceOf[Reference])
            }
          } else {
            // skip
          }
        } else {
          if (!value.isInstanceOf[Reference]) {
            this.update(name, value)
          } else {
            this.setRef(name, value.asInstanceOf[Reference])
          }
        }
      }
    }

    // 4) Update global variables
    joinResult.joinedGlobals.foreach {
      g => this.addToGlobal(g)
    }

    // 5) Update static class fields
    joinResult.joinedStaticClassVariables.foreach {
      case (c, m) => {
        m.foreach {
          case (f, v) => {
            this.setStaticClassField(c, f, v)
          }
        }
      }
    }

    // 6) Update constant definitions
    joinResult.joinedConstants.foreach {
      case (n, c) => this.defineConstant(n, c)
    }

    // 7) Update function definitions
    joinResult.joinedFunctionDefs.foreach {
      case (name, f) => this.defineFunction(f)
    }

    // 8) Update class definitions
    joinResult.joinedClassDefs.foreach {
      case (name, c) => this.addClass(c)
    }
    
    // 9) Touched string literals
    joinResult.joinedTouchedStringLiterals.foreach {
      sv => this.recordTouchedLiteral(sv)
    }
    
    // 10) Include expressions
    joinResult.joinedIncludeHistory.foreach {
      case (k, v) => this.recordIncludeExpression(k._1, k._2, v) // methode haendelt das schon..
    }
    
    // 11) unknown functions
    joinResult.joinedUndefinedfunctions.foreach {
      case (k, v) => {
        if (unknown_standard_functions.contains(k)) {
          unknown_standard_functions.put(k, unknown_standard_functions.get(k).get + v)
        } else {
          unknown_standard_functions.put(k, v)
        }
      }
    }
  }

  def getDelta(): Delta = {
    var returnMap = AnyRefMap[String, OakValue]()
    if (variables.contains("$return")) {
      returnMap += ("$return" -> getRef("$return"))
    }
    if (variables.contains("$returnref")) {
      returnMap += ("$returnref" -> getRef("$returnref"))
    }

    globalVariables.foreach {
      gv =>
        {
          //        if (!(variables.keySet contains gv)) {
          //          update(gv, NullValue(gv))
          //        }
          //        if ( ! this.extract(this.getRef(gv, false)).isInstanceOf[ArrayValue]) {
          try {
            returnMap += (gv -> this.getRef(gv, true))
            //        }
          } catch {
            case e: VariableNotFoundException => {
              //logger.info("Variable not found: " + gv)
            }
          }

        }
    }

    var t = AnyRefMap[String, Map[String, OakValue]]()
    this.staticClassFields.foreach {
      case (m1, m2) => t.put(m1, m2.toMap)
    }

    /*if (this.isSymbolic() && this.isSingleBranch()) {
      val re = lookup("$return")
      update("$return", Choice.optimized(constraint, if (re == null) NullValue else re, NullValue))
    }*/

    new Delta(this.getOutput(), if (!this.isFunctionEnv()) variables else returnMap, references, t, this.globalVariables.toSet, constants.toMap, funcs, classDefs, touched.toSet, include_history.toMap, unknown_standard_functions.toMap)
  }

  //  def weaveReferences(that: Environment) {
  //    val references = that.getHeap.varval.filter { case (ref, value) => !(this.heap.varval.keySet contains ref) }
  //    this.heap.varval = (this.heap.varval.toSeq ++ references.toSeq).toMap
  //    
  //  }

  /**
   * Creates a new Heap linked to the parents heap
   */
  //  def copyHeap(): OakHeap = {
  //    val copy = new OakHeap(heap.varval)
  //    copy.varval = heap.varval
  //    copy
  //  }

  def getStaticClassField(className: String, fieldName: String): OakValue = {
    if (!this.staticClassFields.get(className).isEmpty && !this.staticClassFields.get(className).get.get(fieldName).isEmpty) {
      this.staticClassFields.get(className).get.get(fieldName).get
    } else if (this.parent != null) {
      this.parent.getStaticClassField(className, fieldName)
    } else {
      getClassDef(className).getStaticFields().get(fieldName).get
    }
  }

  def setStaticClassField(className: String, fieldName: String, value: OakValue) = {
    changed = true
    if (this.staticClassFields.get(className).isEmpty) {
      this.staticClassFields += (className -> collection.mutable.Map[String, OakValue]())
    }
    this.staticClassFields.get(className).get.put(fieldName, value)
  }

  /**
   * Defines a constant used during the program execution.
   *
   * @param name Name of the constant
   * @param value Value of the Constant
   */
  def defineConstant(name: String, value: OakValue) {
    constants.put(name, value)
  }

  /**
   * Defines a constant used during the program execution.
   *
   * @param name Name of the constant
   * @param value Value of the Constant
   */
  def getConstant(name: String): OakValue = {
    if (!constants.get(name).isEmpty) {
      constants.get(name).get
    } else if (parent != null) {
      parent.getConstant(name)
    } else {
      StringValue("", "", 0) //NullValue("not found")//
    }
  }

  def defineFunction(f: FunctionDef): Unit = {
    funcs.put(f.getName.toLowerCase, f)
  }

  def getFunction(name: String): FunctionDef = {
    if (!funcs.get(name).isEmpty) {
      funcs.get(name).get
    } else if (parent != null) {
      parent.getFunction(name)
    } else {
      throw new RuntimeException("Function " + name + " not defined!")
    }
  }

  /**
   * Adds a class definition to the environment.
   * @param value ClassDef to add
   */
  def addClass(value: ClassDef) {
    val cname = value.getName
    this.classDefs += (cname.slice(cname.lastIndexOf("\\") + 1, cname.size) -> value)
  }

  /**
   * Looks up a class definition in the environment.
   * @param name Name of the class
   * @return corresponding class definition
   */
  def getClassDef(cname: String): ClassDef = {
    val name = cname.slice(cname.lastIndexOf("\\") + 1, cname.size)
    if (!classDefs.get(name).isEmpty) {
      classDefs.get(name).get
    } else if (parent != null) {
      parent.getClassDef(name)
    } else {
      throw new VariableNotFoundException("ClassDef " + name + " not defined!")
    }
  }

  def containsFunction(name: String) = !funcs.get(name).isEmpty

  def hasTerminated(): Boolean = this.terminated

  def resurrect() {
    this.terminated = false
  }

  // Triggers termination of the environment
  def terminate() {
    this.terminate(getCalls().size)
  }

  private def terminate(call_stack_size: Int) {

    // This environment -> terminated
    terminated = true

    /* Environment is not the root environment && parent environment 
     * has the same call stack size.
     */
    if (parent != null && parent.getCalls().size == call_stack_size) {

      /* Special case: If this environment is a branch, check, whether it is a concrete
      * branch choice or not */
      if (!this.isSymbolic()) {
        parent.terminate(call_stack_size)
      }
    } 
  }
  
  def recordTouchedLiteral(sv: StringValue) {
    this.touched.add(sv)
  }
  
  def recordIncludeExpression(file: String, line: Int, success: Boolean) {
    if (!include_history.contains((file, line))) { // noch nicht besucht in diesem environment
      include_history.put((file, line), success)
    } else {
      if (!include_history.get((file, line)).get && success) { // schon mal besucht, aber nicht erfolgreich· nun aber
        include_history.put((file, line), true)
      }
    }
  }
  
  def info() = {

    val variables = (List(this) ++ getParents).map(e => e.variables.keySet).fold(Set[String]())(_ union _).toSeq.sorted
    val var_val = variables.map(v => (v -> lookup(v))).toMap
    
    val constants = (List(this) ++ getParents).map(e => e.constants.keySet).fold(Set[String]())(_ union _).toSeq.sorted
    val const_val = constants.map(c => (c -> getConstant(c))).toMap
    
    println(s"### ${getConstraint()} ###")
    var_val.foreach {
      case (k, v) => {
        v match {
          case av: ArrayValue => {
            val ar = av.array.map {
              case (kk, vv) => {
                (kk, extract(vv))
              }
            }
            println(k, ar)
          }
          case _ => {
            println(k, v)
          }
        }
      }
    }
    const_val.foreach {
      case (k, v) => println(k, v)
    }
  }
}

/**
 * Static factory methods for environments using different
 * configurations.
 */
object Environment {

  var forks = 0

  /**
   * Splits an environment into two branch environments that
   * can be joined afterwards.
   *
   * @param newConstraint Path constrained to add to the branches
   * @param Tuple of two branch environments
   */
  private def simpleFork(parent: Environment, newConstraint: Constraint): (BranchEnv, BranchEnv) = {
    Environment.forks += 1
    val b1 = new BranchEnv(parent, parent.getCalls(), newConstraint)
    val b2 = new BranchEnv(parent, parent.getCalls(), newConstraint.NOT())

    copyGlobalVariables(parent, b1)
    copyGlobalVariables(parent, b2)

    return (b1, b2)
  }

  def fork(environment: Environment, conditions: List[Constraint]): List[BranchEnv] = {
    val forked = Environment.simpleFork(environment, conditions(0))
    forked._1.age = environment.age + 1
    forked._2.age = environment.age + 1
    if (conditions.size == 1) {
      List(forked._1, forked._2)
    } else {
      forked._1 :: fork(forked._2, conditions.tail)
    }
  }

  private def copyGlobalVariables(parent: Environment, env: Environment) {
    parent.globalVariables.foreach {
      gvn =>
        try {
          env.addToGlobal(gvn)
        } catch {
          case vnfe: VariableNotFoundException => {}
        }
    }
  }

  /**
   * Creates a new function environment that is used to
   * execute a
   *
   *  - function call or
   *  - method call
   *
   * The function call is documented via the
   * environment's call stack.
   *
   * @param dis Parent environment to bounce output to
   * @param f Name of the function
   * @return FunctionEnv
   */
  def createFunctionEnvironment(dis: Environment, fc: Call): Environment = {
    val env = new Environment(dis, dis.getCalls push fc, dis.getConstraint)
    env.age = dis.age + 1

    copyGlobalVariables(dis, env)

    env
  }

  /**
   * Creates a new object environment that is used to
   * execute a function call.
   *
   * @param dis Parent environment to bounce output to
   * @param f Name of the function
   * @return ObjectEnv
   */
  def createObjectEnvironment(dis: Environment, obj: ObjectValue): Environment = {
    val env = new Environment(dis, dis.getCalls(), dis.getConstraint)
    env.update("$this", obj)
    env.age = dis.age + 1

    copyGlobalVariables(dis, env)

    env
  }

  /**
   * Creates a new function environment that is used to
   * execute a
   *
   *  - function call or
   *  - method call
   *
   * The function call is documented via the
   * environment's call stack.
   *
   * @param dis Parent environment to bounce output to
   * @param f Name of the function
   * @return FunctionEnv
   */
  def createMethodEnvironment(dis: Environment, obj: ObjectValue, mc: Call): Environment = {
    val env = createFunctionEnvironment(dis, mc)
    env.update("$this", obj)
    env.age = dis.age + 1

    copyGlobalVariables(dis, env)

    env
  }

  def createLoopEnvironment(dis: Environment): LoopEnv = {
    val env = new LoopEnv(dis, dis.getCalls, dis.getConstraint)
    env.age = dis.age + 1

    copyGlobalVariables(dis, env)

    env
  }
  
  

}