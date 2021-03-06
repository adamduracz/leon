/* Copyright 2009-2014 EPFL, Lausanne */

package leon
package synthesis

import purescala.Common._
import purescala.Trees._
import purescala.TreeOps._
import purescala.Definitions._
import purescala.Constructors._

object ConvertWithOracle extends LeonPhase[Program, Program] {
  val name        = "Convert WithOracle to Choose"
  val description = "Convert WithOracle found in bodies to equivalent Choose"

  /**
   * This phase converts a body with "withOracle{ .. }" into a choose construct:
   *
   * def foo(a: T) = {
   *   require(..a..)
   *   withOracle { o =>
   *     expr(a,o) ensuring { x => post(x) }
   *   }
   * }
   *
   * gets converted into:
   *
   * def foo(a: T) {
   *   require(..a..)
   *   val o = choose { (o) => {
   *     val res = expr(a, o)
   *     pred(res)
   *   }
   *   expr(a,o)
   * } ensuring { res =>
   *   pred(res)
   * }
   *
   */
  def run(ctx: LeonContext)(pgm: Program): Program = {

    pgm.definedFunctions.foreach(fd => {
      if (fd.hasBody) {
        val body = preMap {
          case wo @ WithOracle(os, b) =>
            withoutSpec(b) match {
              case Some(body) =>
                val chooseOs = os.map(_.freshen)

                val pred = postconditionOf(b) match {
                  case Some((id, post)) =>
                    replaceFromIDs((os zip chooseOs.map(_.toVariable)).toMap, Let(id, body, post))
                  case None =>
                    BooleanLiteral(true)
                }

                Some(letTuple(os, tupleChoose(Choose(chooseOs, pred)), b))
              case None =>
                None
            }
          case _ => None
        }(fd.body.get)

        fd.body = Some(body)
      }

      // Ensure that holes are not found in pre and/or post conditions
      fd.precondition.foreach {
        preTraversal{
          case _: WithOracle =>
            ctx.reporter.error("WithOracle expressions are not supported in preconditions. (function "+fd.id.asString(ctx)+")")
          case _ =>
        }
      }

      fd.postcondition.foreach { case (id, post) =>
        preTraversal{
          case _: WithOracle =>
            ctx.reporter.error("WithOracle expressions are not supported in postconditions. (function "+fd.id.asString(ctx)+")")
          case _ =>
        }(post)
      }

    })

    pgm
  }
}
