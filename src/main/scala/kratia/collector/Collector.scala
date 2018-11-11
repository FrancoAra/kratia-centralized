package kratia.collector

import cats._
import cats.data.NonEmptyList
import cats.implicits._
import kratia.collector.Collector._
import kratia.collector.CollectorEvent.Voted
import lambdaone.toolbox.{CRUDStore, EventStore, UniqueGen}

trait Collector[F[_], Address, P] {

  def create(ballot: Ballot[P], nickname: String): F[BallotBox[Address, P]]

  def vote(ballotBox: BallotBox[Address, P], vote: Vote[Address, P]): F[ProofOfVote[Address]]

  def validateVote(proofOfVote: ProofOfVote[Address]): F[Boolean]

  def inspect(ballotBox: BallotBox[Address, P]): F[InfluenceAllocation[P]]

}

object Collector {

  type Influence = Double

  type InfluenceAllocation[P] = Map[P, Influence]

  sealed class BinaryProposal

  object BinaryProposal {

    object Yes extends BinaryProposal

    object No extends BinaryProposal

    val AllChoices = NonEmptyList.fromListUnsafe(List(Yes, No))

  }

  case class Vote[Address, P](ballot: Ballot[P], memberAddresss: Address, influenceAllocation: InfluenceAllocation[P])

  case class Ballot[P](p: NonEmptyList[P]) extends AnyVal

  def binaryBallot = Ballot[BinaryProposal](BinaryProposal.AllChoices)

  case class BallotBox[Address, P](address: Address, nickname: String)

  case class ProofOfVote[Address](ref: Address, memberAddress: Address)

}

object CollectorCQRS {

  implicit def apply[F[_] : Monad, A, P](implicit
                                         event: EventStore[F, CollectorEvent[A, P]],
                                         query: CRUDStore[F, A, (A, Vote[A, P])],
                                         uniqueGen: UniqueGen[F, A]
                                        ): CollectorCQRS[F, A, P]
  = new CollectorCQRS(event, query, uniqueGen)
}

/*
Crud store: I is proofOfVote.Id
            D is (Ballotbox.id, Vote)
 */
class CollectorCQRS[F[_] : Monad, A, P](
                                         event: EventStore[F, CollectorEvent[A, P]],
                                         query: CRUDStore[F, A, (A, Vote[A, P])],
                                         uniqueGen: UniqueGen[F, A]
                                       ) extends Collector[F, A, P] {

  override def create(ballot: Ballot[P], nickname: String): F[BallotBox[A, P]] =
    for {
      address <- uniqueGen.gen
      _ <- event.emit(CollectorEvent.CreatedBallotBox(address, ballot))
    } yield BallotBox(address, nickname)

  override def vote(ballotBox: BallotBox[A, P], vote: Vote[A, P]): F[ProofOfVote[A]] = {
    for {
      proof <- uniqueGen.gen
      _ <- event.emit(Voted(proof, ballotBox, vote))
    } yield ProofOfVote(proof, vote.memberAddresss)
  }

  override def validateVote(proofOfVote: ProofOfVote[A]): F[Boolean] =
    query.exists(proofOfVote.ref)

  override def inspect(ballotBox: BallotBox[A, P]): F[InfluenceAllocation[P]] =
    query.filter {
      case (ballotBoxRef, Vote(_, _, _)) => ballotBoxRef == ballotBox.address
    }.map {
      _.map { case (_, Vote(_, _, influenceAllocation)) => influenceAllocation }
    }.map(_.combineAll)
}

sealed trait CollectorEvent[A, P]

object CollectorEvent {

  case class CreatedBallotBox[A, P](ref: A, ballot: Ballot[P]) extends CollectorEvent[A, P]

  case class Voted[A, P](proof: A, ballotBox: BallotBox[A, P], vote: Vote[A, P]) extends CollectorEvent[A, P]

}

