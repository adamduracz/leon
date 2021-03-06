/* Copyright 2009-2014 EPFL, Lausanne */

package leon
package termination

import leon.purescala.Definitions._
import leon.purescala.Trees._
import leon.purescala.TreeOps._
import leon.purescala.TypeTrees._
import leon.purescala.TypeTreeOps._
import leon.purescala.Common._

import scala.collection.mutable.{Map => MutableMap}

final case class Chain(relations: List[Relation]) {

  private def identifier: Map[(Relation, Relation), Int] = {
    (relations zip (relations.tail :+ relations.head)).groupBy(p => p).mapValues(_.size)
  }

  override def equals(obj: Any): Boolean = obj match {
    case (chain : Chain) => chain.identifier == identifier
    case _ => false
  }

  override def hashCode(): Int = identifier.hashCode

  lazy val funDef  : FunDef      = relations.head.funDef
  lazy val funDefs : Set[FunDef] = relations.map(_.funDef).toSet

  lazy val size: Int = relations.size

  private lazy val inlining : Seq[(Seq[ValDef], Expr)] = {
    def rec(list: List[Relation], funDef: TypedFunDef, subst: Map[Identifier, Expr]): Seq[(Seq[ValDef], Expr)] = list match {
      case Relation(_, _, fi @ FunctionInvocation(fitfd, args), _) :: xs =>
        val tfd = TypedFunDef(fitfd.fd, fitfd.tps.map(funDef.translated(_)))
        val expr = replaceFromIDs(subst, hoistIte(expandLets(matchToIfThenElse(tfd.body.get))))

        val mappedArgs = args.map(e => replaceFromIDs(subst, tfd.translated(e)))
        val newSubst = (tfd.params.map(_.id) zip mappedArgs).toMap
        (tfd.params, expr) +: rec(xs, tfd, newSubst)
      case Nil => Seq.empty
    }

    val body = hoistIte(expandLets(matchToIfThenElse(funDef.body.get)))
    val tfd = funDef.typed(funDef.tparams.map(_.tp))
    (tfd.params, body) +: rec(relations, tfd, funDef.params.map(arg => arg.id -> arg.toVariable).toMap)
  }

  lazy val finalParams : Seq[ValDef] = inlining.last._1

  def loop(initialSubst: Map[Identifier, Identifier] = Map(), finalSubst: Map[Identifier, Identifier] = Map()) : Seq[Expr] = {
    def rec(relations: List[Relation], funDef: TypedFunDef, subst: Map[Identifier, Identifier]): Seq[Expr] = {
      val translate : Expr => Expr = {
        val map : Map[Expr, Expr] = subst.map(p => p._1.toVariable -> p._2.toVariable)
        (e: Expr) => replace(map, funDef.translated(e))
      }

      val Relation(_, path, fi @ FunctionInvocation(fitfd, args), _) = relations.head
      val tfd = TypedFunDef(fitfd.fd, fitfd.tps.map(funDef.translated(_)))

      lazy val newArgs = args.map(translate(_))

      path.map(translate(_)) ++ (relations.tail match {
        case Nil => if (finalSubst.isEmpty) Seq.empty else {
          (tfd.params.map(vd => finalSubst(vd.id).toVariable) zip newArgs).map(p => Equals(p._1, p._2))
        }
        case xs =>
          val params = tfd.params.map(_.id)
          val freshParams = tfd.params.map(arg => FreshIdentifier(arg.id.name, true).setType(arg.tpe))
          val bindings = (freshParams.map(_.toVariable) zip newArgs).map(p => Equals(p._1, p._2))
          bindings ++ rec(xs, tfd, (params zip freshParams).toMap)
      })
    }

    rec(relations, funDef.typed(funDef.tparams.map(_.tp)), initialSubst)
  }

  /*
  def reentrant(other: Chain) : Seq[Expr] = {
    assert(funDef == other.funDef)
    val bindingSubst = funDef.params.map(vd => vd.id -> vd.id.freshen).toMap
    val firstLoop = loop(finalSubst = bindingSubst)
    val secondLoop = other.loop(initialSubst = bindingSubst)
    firstLoop ++ secondLoop
  }
  */

  lazy val cycles : Seq[List[Relation]] = (0 to relations.size - 1).map { index =>
    val (start, end) = relations.splitAt(index)
    end ++ start
  }

  def compose(that: Chain) : Set[Chain] = {
    val map = relations.zipWithIndex.map(p => p._1.call.tfd.fd -> ((p._2 + 1) % relations.size)).groupBy(_._1).mapValues(_.map(_._2))
    val tmap = that.relations.zipWithIndex.map(p => p._1.funDef -> p._2).groupBy(_._1).mapValues(_.map(_._2))
    val keys = map.keys.toSet & tmap.keys.toSet

    keys.flatMap(fd => map(fd).flatMap { i1 =>
      val (start1, end1) = relations.splitAt(i1)
      val called = if (start1.isEmpty) relations.head.funDef else start1.last.call.tfd.fd
      tmap(called).map { i2 =>
        val (start2, end2) = that.relations.splitAt(i2)
        Chain(start1 ++ end2 ++ start2 ++ end1)
      }
    }).toSet
  }

  lazy val inlined: Seq[Expr] = inlining.map(_._2)
}

trait ChainBuilder extends RelationBuilder { self: TerminationChecker with Strengthener with RelationComparator =>

  protected type ChainSignature = (FunDef, Set[RelationSignature])

  protected def funDefChainSignature(funDef: FunDef): ChainSignature = {
    funDef -> (self.program.callGraph.transitiveCallees(funDef) + funDef).map(funDefRelationSignature(_))
  }

  private val chainCache : MutableMap[FunDef, (Set[FunDef], Set[Chain], ChainSignature)] = MutableMap.empty

  def getChains(funDef: FunDef)(implicit solver: Processor with Solvable): (Set[FunDef], Set[Chain]) = chainCache.get(funDef) match {
    case Some((subloop, chains, signature)) if signature == funDefChainSignature(funDef) => subloop -> chains
    case _ => {
      val relationConstraints : MutableMap[Relation, SizeConstraint] = MutableMap.empty

      def decreasing(relations: List[Relation]): Boolean = {
        val constraints = relations.map(relation => relationConstraints.get(relation).getOrElse {
          val Relation(funDef, path, FunctionInvocation(fd, args), _) = relation
          val (e1, e2) = (Tuple(funDef.params.map(_.toVariable)), Tuple(args))
          val constraint = if (solver.definitiveALL(Implies(And(path), self.softDecreasing(e1, e2)))) {
            if (solver.definitiveALL(Implies(And(path), self.sizeDecreasing(e1, e2)))) {
              StrongDecreasing
            } else {
              WeakDecreasing
            }
          } else {
            NoConstraint
          }

          relationConstraints(relation) = constraint
          constraint
        }).toSet

        !constraints(NoConstraint) && constraints(StrongDecreasing)
      }

      def chains(seen: Set[FunDef], chain: List[Relation]) : (Set[FunDef], Set[Chain]) = {
        val Relation(_, _, FunctionInvocation(tfd, _), _) :: xs = chain
        val fd = tfd.fd

        if (!self.program.callGraph.transitivelyCalls(fd, funDef)) {
          Set.empty[FunDef] -> Set.empty[Chain]
        } else if (fd == funDef) {
          Set.empty[FunDef] -> Set(Chain(chain.reverse))
        } else if (seen(fd)) {
          Set(fd) -> Set.empty[Chain]
        } else {
          val results = getRelations(fd).map(r => chains(seen + fd, r :: chain))
          val (funDefs, allChains) = results.unzip
          (funDefs.flatten, allChains.flatten)
        }
      }

      val results = getRelations(funDef).map(r => chains(Set.empty, r :: Nil))
      val (funDefs, allChains) = results.unzip

      val loops = funDefs.flatten
      val filteredChains = allChains.flatten.filter(chain => !decreasing(chain.relations))

      chainCache(funDef) = (loops, filteredChains, funDefChainSignature(funDef))

      loops -> filteredChains
    }
  }
}
