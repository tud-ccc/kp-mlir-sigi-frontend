package de.cfaed.sigi

import ast.*
import datamodel.{TypeDescriptor, TypeParm}
import types.*
import types.StackType.canonicalize

import de.cfaed.sigi.repl.Env

import java.io.PrintStream
import scala.io
import scala.annotation.tailrec
import scala.collection.mutable

package emitmlir {

  import de.cfaed.sigi.emitmlir.MlirBuilder.{BuiltinIntOps, TargetFunType}

  import scala.collection.mutable.ListBuffer
  import scala.io.Source

  sealed trait MlirValue {

  }

  class MlirIdent(prefix: String)(id: String) extends MlirValue {
    override def toString: String = "%" + prefix + id

  }

  class MlirSymbol(prefix: String)(id: String) extends MlirValue, Comparable[MlirSymbol] {
    val simpleName: String = prefix + id

    override def toString: String = "@" + simpleName

    override def compareTo(o: MlirSymbol): Int = simpleName.compareTo(o.simpleName)

  }

  class IdGenerator[T](start: Int, maker: String => T) {
    private var seqNumber: Int = _
    private var curImpl: T = _
    reset()

    def cur: T = curImpl

    def next(suffix: String = ""): T = {
      seqNumber += 1
      curImpl = maker(seqNumber.toString + suffix)
      cur
    }

    def reset(): Unit = {
      seqNumber = start
      curImpl = maker(start.toString)
    }
  }

  // helper type that represents a push operation
  sealed trait SigiDialectOp

  class MPopOp(val ty: String,
               val inEnv: MlirIdent,
               val outEnv: MlirIdent,
               val outVal: MlirIdent) extends SigiDialectOp

  class MPushOp(val ty: String,
                val inEnv: MlirIdent,
                val outEnv: MlirIdent,
                val inVal: MlirIdent) extends SigiDialectOp


  class MlirBuilder(val out: PrintStream,
                    private var indent: Int = 0,
                    startEnv: Int = 0,
                    startVal: Int = 0) {
    /** Ident of the environment. */
    private val envIdGen = IdGenerator(startEnv, new MlirIdent("e")(_))
    /** Ident for regular values. */
    private val valIdGen = IdGenerator(startVal, new MlirIdent("")(_))

    private case class LocalSymDesc(sourceName: String, mlirName: MlirIdent, mlirType: String)

    /** Map of variable name (Sigi) to mlir ident for those
      * variables that were put on the
      * runtime stack by a [[TNameTopN]] node.
      */
    private val localSymEnv = mutable.Map[String, LocalSymDesc]()

    private def resetLocals(): Unit = {
      envIdGen.reset()
      valIdGen.reset()
      localSymEnv.clear()
    }

    private def mlirType(ty: KStackTypeItem): String = ty match
      case KInt => "i32"
      case KBool => "i1"
      case KString => "!sigi.str"
      case KFun(stack) =>
        val consumed = stack.consumes.map(mlirType).mkString(",")
        val produced = stack.produces.map(mlirType).mkString(",")
        s"!sigi.fun<[$consumed], [$produced]>"
      case KList(item) => s"!sigi.list<${mlirType(item)}>"
      case _ => throw new IllegalArgumentException(s"Cannot emit type with variable $ty")

    private def makeConstAttr[T](ty: KPrimitive[T], value: T): String = ty match
      case KInt => value.toString
      case KBool => if value then "1" else "0"
      case KString => s"\"$value\""

    def println(str: String): Unit = {
      (0 until indent).foreach { _ => out.print("    ") }
      out.println(str)
    }

    def emitFunction(funDef: TFunDef): Unit = {
      resetLocals()
      val TFunDef(name, ty, body) = funDef

      println(s"// $name: $ty")
      println(s"func.func @$name(${envIdGen.cur}: !sigi.stack) -> !sigi.stack {")
      indent += 1
      val uniqued = makeNamesUnique(body)
      this.emitExpr(uniqued)
      println(s"return ${envIdGen.cur}: !sigi.stack")
      indent -= 1
      println(s"}")
    }

    private def renderOp(op: SigiDialectOp, comment: String = ""): Unit = {
      val realComment = if comment.isEmpty then "" else s" // $comment"

      op match
        case op: MPopOp =>
          println(s"${op.outEnv}, ${op.outVal} = sigi.pop ${op.inEnv}: ${op.ty}$realComment")
        case op: MPushOp =>
          println(s"${op.outEnv} = sigi.push ${op.inEnv}, ${op.inVal}: ${op.ty}$realComment")
    }

    private def typeToStr(t: String | KStackTypeItem) = t match
      case s: String => s
      case t: KStackTypeItem => mlirType(t)

    private def renderPush(ty: KStackTypeItem | String,
                           inVal: MlirIdent,
                           inEnv: MlirIdent = envIdGen.cur,
                           comment: String = ""): Unit =
      val push = new MPushOp(ty = typeToStr(ty),
                             inEnv = inEnv,
                             outEnv = envIdGen.next(),
                             inVal = inVal)
      renderOp(push, comment)

    private def makePop(ty: KStackTypeItem | String,
                        valueNameSuffix: String = ""): MPopOp =
      new MPopOp(ty = typeToStr(ty),
                 inEnv = envIdGen.cur,
                 outEnv = envIdGen.next(),
                 outVal = valIdGen.next(valueNameSuffix))

    /**
      * Contract: before this fun is invoked, the [[envIdGen]] is the ID of the last environment.
      * When it exits, the [[envIdGen]] is the ID of another env id.
      *
      * Assumptions: every name is unique in the given term.
      *
      */
    private def emitExpr(t: TypedExpr): Unit =
      val envId = envIdGen.cur
      t match
        case TChain(_, a, b) =>
          emitExpr(a)
          emitExpr(b)

        case TPushList(ty, items) =>
          val itemIds = for (item <- items) yield {
            emitExpr(item)
            val pop = makePop(ty.item)
            renderOp(pop)
            pop.outVal
          }
          val args = itemIds.mkString(", ")
          val listId = valIdGen.next()

          val mlirTy = mlirType(ty)

          println(s"$listId = sigi.create_list $args: $mlirTy")
          renderPush(ty, listId)

        case TPushPrim(ty, value) if ty == KBool || ty == KInt =>
          val cstId = valIdGen.next()
          val cstAttr = makeConstAttr(ty, value)
          val mlirTy = mlirType(ty)

          println(s"$cstId = arith.constant $cstAttr: $mlirTy")
          renderPush(ty, cstId)

        case TPushPrim(ty, value) =>
          val cstId = valIdGen.next()
          val cstAttr = makeConstAttr(ty, value)
          val mlirTy = mlirType(ty)

          println(s"$cstId = sigi.constant $cstAttr: $mlirTy")
          renderPush(ty, cstId)


        // just pushing one item
        case TFunApply(StackType(Nil, List(ty)), name) =>
          localSymEnv.get(name) match
            case Some(LocalSymDesc(_, mlirId, _)) => renderPush(ty, mlirId, comment = name)
            case None =>
              val errId = envIdGen.next()
              println(s"$errId = sigi.error $envId, {msg=\"undefined name '$name'\"}")

        // builtin arithmetic and comparisons
        case TFunApply(StackType(List(a@(KInt | KBool), b), List(c)), name) if a == b && BuiltinIntOps.contains(name) =>
          val pop0 = makePop(b)
          val pop1 = makePop(a)

          val result = valIdGen.next()
          val builtinOp = BuiltinIntOps(name)

          renderOp(pop0)
          renderOp(pop1)
          println(s"$result = $builtinOp ${pop0.outVal}, ${pop1.outVal}: ${mlirType(a)}")
          renderPush(c, result)

        // intrinsics
        case TFunApply(StackType(List(a), _), "dup") =>
          val pop = makePop(a)
          renderOp(pop)
          renderPush(a, pop.outVal)
          renderPush(a, pop.outVal)

        case TFunApply(StackType(List(a), _), "pop") =>
          renderOp(makePop(a))

        case TFunApply(StackType(List(a, b), _), "swap") =>
          val popb = makePop(b)
          val popa = makePop(a)
          renderOp(popb)
          renderOp(popa)
          renderPush(b, popb.outVal)
          renderPush(a, popa.outVal)

        // higher-order function.
        case TFunApply(_, "apply") =>
          val pop = makePop(MlirBuilder.ClosureT)
          renderOp(pop)
          val nextEnv = envIdGen.next()
          println(s"$nextEnv = closure.call ${pop.outVal} (${pop.outEnv}) : ${MlirBuilder.ClosureT}")

        // general case of function calls.
        //  todo monomorphise generic calls, make sure all terms are ground
        case TFunApply(ty, name) =>
          // assume the function has been emitted with the given name (this is after monomorphisation)

          val escapedName = if name.matches("[a-zA-Z]\\w*") then name else s"\"$name\""
          val resultEnv = envIdGen.next()
          println(s"$resultEnv = func.call @$escapedName($envId) : $TargetFunType // $ty")


        case TPushQuote(term) =>

          val freeVars = collectFreeVars(term)
          val capturedVars = localSymEnv.view.filterKeys(freeVars)
          val prevBindings = capturedVars.toMap // save previous bindings
          val capturedArgsMlir = ListBuffer.empty[String]
          val capturedVarsWithNewIds = capturedVars.map { e =>
            val (name, LocalSymDesc(sourceName, mlirId, mlirType)) = e
            // give the variable a fresh name
            val captId = valIdGen.next(s"_$sourceName")
            capturedArgsMlir += s"$captId = $mlirId : $mlirType"
            (name, LocalSymDesc(sourceName, captId, mlirType))
          }.toMap

          // replace those keys
          this.localSymEnv ++= capturedVarsWithNewIds
          val capturedArgsToString = capturedArgsMlir.mkString(", ")

          val cstId = valIdGen.next()
          val closureEnv = envIdGen.next()
          println(s"$cstId = closure.box [$capturedArgsToString] ($closureEnv : !sigi.stack) -> !sigi.stack { // ${term.stackTy}")
          indent += 1
          emitExpr(term)
          println(s"closure.return ${envIdGen.cur}: !sigi.stack")
          indent -= 1
          println(s"}")
          renderPush(MlirBuilder.ClosureT, inVal = cstId, inEnv = envId)

          // restore previous bindings
          this.localSymEnv ++= prevBindings

        case TNameTopN(stackTy, names) =>
          for ((name, ty) <- names.zip(stackTy.consumes)) {
            val pop = makePop(ty, valueNameSuffix = s"_$name")
            val desc = LocalSymDesc(sourceName = name, mlirName = pop.outVal, mlirType = mlirType(ty))
            localSymEnv.put(name, desc)
            renderOp(pop, comment = name)
          }

        case TEvalBarrier(_) => throw UnsupportedOperationException("Eval barrier is only for the REPL")

    /** Makes all names defined within this term unique, so as
      * to remove name shadowing entirely.
      */
    private def makeNamesUnique(term: TypedExpr): TypedExpr = {
      case class DedupVarScope(map: Map[String, String]) extends (String => String) {
        override def apply(name: String): String = map.getOrElse(name, name)

        def makeUnique(names: IterableOnce[String]): DedupVarScope = {
          val newMap = names.iterator.foldLeft(map) { (map, name) =>
            map + (name -> map.get(name).map(_ + "x").getOrElse(name))
          }
          DedupVarScope(newMap)
        }
      }

      def makeNamesUniqueRec(scope: DedupVarScope, term: TypedExpr): (DedupVarScope, TypedExpr) = {
        term match
          case TChain(ty, a, b) =>
            val (aScope, newA) = makeNamesUniqueRec(scope, a)
            val (bScope, newB) = makeNamesUniqueRec(aScope, b)
            (bScope, TChain(ty, newA, newB))
          case TFunApply(ty, name) => (scope, TFunApply(ty, scope(name)))
          case TNameTopN(ty, names) =>
            val dedupedNames = scope.makeUnique(names)
            (dedupedNames, TNameTopN(ty, names.map(dedupedNames)))
          case TPushList(ty, items) =>
            val (lastScope, newItems) = items.foldLeft((scope, List[TypedExpr]())) { (prev, item) =>
              val (scope, newItems) = prev
              val (scope2, newItem) = makeNamesUniqueRec(scope, item)
              (scope2, newItem :: newItems)
            }
            (lastScope, TPushList(ty, newItems))
          case TPushQuote(term) =>
            val (tscope, newTerm) = makeNamesUniqueRec(scope, term)
            (tscope, TPushQuote(newTerm))
          case term => (scope, term)
      }

      makeNamesUniqueRec(DedupVarScope(Map()), term)._2
    }

    /**
      * Collects all free vars within a term.
      * This is used to determine captured variables in a closure.
      */
    private def collectFreeVars(term: TypedExpr): Set[String] = {
      case class VarScope(used: Set[String], bound: Set[String])

      def collectFreeVarsRec(term: TypedExpr, scope: VarScope): VarScope = {
        term match
          case TChain(_, a, b) =>
            val aScope = collectFreeVarsRec(a, scope)
            collectFreeVarsRec(b, aScope)
          case TFunApply(_, name) => scope.copy(used = scope.used + name)
          case TNameTopN(_, names) => scope.copy(bound = scope.bound ++ names)
          // no other term affects scope
          case _ => scope
      }

      val VarScope(used, bound) = collectFreeVarsRec(term, VarScope(Set.empty, Set.empty))
      used -- bound
    }
  }

  object MlirBuilder {
    private val TargetFunType = "(!sigi.stack) -> !sigi.stack"
    private val ClosureT = s"!closure.box<$TargetFunType>"
    private val BuiltinIntOps = Map(
      "+" -> "arith.addi",
      "-" -> "arith.subi",
      "*" -> "arith.muli",
      "/" -> "arith.divi",
      "%" -> "arith.modi",
      "<=" -> "arith.cmpi \"sle\",",
      ">=" -> "arith.cmpi \"sge\",",
      "<" -> "arith.cmpi \"slt\",",
      ">" -> "arith.cmpi \"sgt\",",

      "=" -> "arith.cmpi \"eq\",",
      "<>" -> "arith.cmpi \"ne\",",
      )

  }

  /*
  Overview of generation steps:
  - Each expression is technically a function of type ((!sigi.stack) -> !sigi.stack). Most of them are
   generated inline though. We use the statement as a codegen boundary because it's also a scoping
   boundary.
  - Each function is generated into its own func of type ((!sigi.stack) -> !sigi.stack)
  - Each quote is mapped to a !closure.box<(!sigi.stack) -> !sigi.stack>
    - they may capture their environment. This makes memory safety complicated, in the general
    case we need garbage collection. Let's restrict quotes to only capture closures and integers.
  - Function calls (= variable access):
    - builtins map to builtin operators of the sigi dialect
    - user-defined funs must have a static binding.

  */

  def emitModule(out: PrintStream)(module: TModule): Unit = {
    out.println("module {")
    val builder = new MlirBuilder(out, indent = 1)
    for ((_, fun) <- module.functions) {
      builder.emitFunction(fun)
    }

    val mainFun = TFunDef("__main__", module.mainExpr.stackTy, module.mainExpr)
    builder.emitFunction(mainFun)

    // todo missing glue code

    out.println("}")
  }

  def parseSigiAndEmitMlir(out: PrintStream)(in: Source): Unit = {

    val env = Env.Default.toTypingScope
    val res = for {
      parsed <- SigiParser.parseFile(in.mkString)
      typed <- types.typeFile(env)(parsed)
    } yield emitModule(out)(typed)

    res match
      case Left(err) => println(err)
      case _ =>
  }

  @main
  def sigiToMlir(): Unit = parseSigiAndEmitMlir(System.out)(io.Source.stdin)
  @main
  def testDumping(): Unit = parseSigiAndEmitMlir(System.out)(io.Source.fromString("1 -> x; { x } 2 -> x; { x }"))


}
