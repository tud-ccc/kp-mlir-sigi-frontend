package de.cfaed.sigi

import repl.Env
import types.*

import de.cfaed.sigi.debug.NoopLogger
import org.scalatest.Inside.inside
import org.scalatest.funsuite.AnyFunSuite

/**
  * @author Clément Fournier &lt;clement.fournier@tu-dresden.de&gt;
  */
class TypeInfSpec extends AnyFunSuite {


  inline def checkType(term: String, ty: String): Unit = {
    test(s"$term should have type $ty") {

      val result: Either[SigiError, StackType] = for {
        parsed <- new ast.SigiParser().parseExpr(term)
        typed <- types.assignType(Env.Default.toTypingScope(using debug.NoopLogger))(parsed)
      } yield typed.stackTy

      assertResult(Right(ty))(result.map(_.toString))
    }
  }




  checkType("1 2 \\swap", "-> int, int, ('a, 'b -> 'b, 'a)")
  checkType("1 2 \\swap apply", "-> int, int")
  checkType("\\swap apply", "'a, 'b -> 'b, 'a")
  checkType("1 swap", "'a -> int, 'a")
  checkType("1 \\swap apply", "'a -> int, 'a")

  checkType("1 2", "-> int, int")
  checkType("1 2 pop", "-> int")
  checkType("{ pop }", "-> ('a ->)")

  checkType("-> x, y; x y", "'a, 'b -> 'a, 'b")
  checkType("-> x;", "'a ->")
  checkType("-> x, y; y", "'a, 'b -> 'b")


  checkType("if (true) 1 else 2", "-> int")
  checkType("if 1 else 2", "bool -> int")
  checkType("true if 1 else 2", "-> int")
  checkType("{->a,b;b} -> \\snd; 1 2 snd", "-> int")

  checkType("1 2 -> x, y; x y", "-> int, int")
  checkType("(1 2 -> x, y;) x y", "-> int, int")
  checkType("1 2 ((-> x, y; x) y)", "-> int, int")
  checkType("1 2 (-> x, y; (x y))", "-> int, int")

  checkType("[]", "-> 'a list")
  checkType("[2]", "-> int list")
  checkType("[{->x; x}]", "-> ('a -> 'a) list")
  checkType("-> x, y; [x, y]", "'a, 'a -> 'a list")
  checkType("-> x, y; x * y", "int, int -> int")
  checkType("-> x, y; x x * y", "int, int -> int, int")

  // this used to execute correctly but type to
  //  -> int, ('a, 'b -> 'b), str
  checkType("\"a\" 2 {->a,b; b} -> \\snd; snd", "-> int")
  // Same as previous. Given application of terms with
  // types (-> 'a, 'b) ('c ->), we should infer that 'b = 'c.
  // This is because the left term will push and 'a, then a 'b,
  // while the right term will pop a 'c.
  // When the arities match, we have (-> 'a, 'b) ('c, 'd ->)
  // where 'a = 'c and 'b = 'd. That's because consumed arguments
  // are popped in reverse.
  checkType("(1 true) pop", "-> int")

  checkType("\\dup dup", "-> ('a -> 'a, 'a), ('a -> 'a, 'a)")

  // Stackoverflow when typing this:
  // 1 2 true if { -> x, y; x } else ({-> x, y ; y} apply)
  // (it's not well formed). The well formed term:
  // (1 2 true if { -> x, y; x } else {-> x, y ; y}) apply
  // types correctly.


  // higher order inference
  checkType("-> f; f apply", "'A, ('A -> 'B) -> 'B")
  checkType("-> f; \\f apply", "'a -> 'a")

  checkType("-> f; f apply -> x; if (x) pass else pass", "'A, ('A -> 'B, bool) -> 'B")



  inline def compatibilityCheck(descr: String, expected: PartialFunction[Either[SigiCompilationError, Unit], Unit])
                               (ty: String, sig: String): Unit = {
    test(s"Type $ty should be $descr with signature type $sig") {

      val parser = new ast.SigiParser()
      val parseResolve = parser.parseAndResolveFunType(Env.Default.toTypingScope(using NoopLogger))
      val result = for {
        tyParsed <- parseResolve(ty)
        sigParsed <- parseResolve(sig)
        _ <- types.checkCompatible(tyParsed, sigParsed)(using NoopLogger).toLeft(())
      } yield ()

      inside(result)(expected)
    }
  }


  inline def checkTypesNotCompatible(ty: String, sig: String)(errorMatcher: PartialFunction[SigiError, Unit] = { case _ => () }): Unit =
    compatibilityCheck("NOT compatible", { case Left(err) if errorMatcher.isDefinedAt(err) => () })(ty, sig)

  inline def checkTypesCompatible(ty: String, sig: String): Unit =
    compatibilityCheck("compatible", { case Right(()) => () })(ty, sig)


  checkTypesCompatible("int -> int", "int -> int")
  checkTypesCompatible("'a, 'b -> 'b", "'a, 'a -> 'a")

  checkTypesNotCompatible("'a, 'a -> int", "'a, 'a -> 'a")()
  checkTypesNotCompatible("'a, 'a -> 'a", "'a, 'b -> 'b")()
  checkTypesNotCompatible("int -> int", "-> int")()

}
