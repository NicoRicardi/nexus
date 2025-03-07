package ch.epfl.bluebrain.nexus.delta.sourcing.postgres

import cats.effect.{IO, Resource}
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.{Execute, Transactors}
import ch.epfl.bluebrain.nexus.testkit.mu.NexusSuite
import ch.epfl.bluebrain.nexus.testkit.postgres.PostgresContainer
import doobie.implicits._
import doobie.postgres.sqlstate
import munit.{catseffect, Location}
import org.postgresql.util.PSQLException

object Doobie {

  val PostgresUser     = "postgres"
  val PostgresPassword = "postgres"

  private def transactors(
      postgres: Resource[IO, PostgresContainer],
      user: String,
      pass: String
  ): Resource[IO, Transactors] = {
    postgres
      .flatMap(container => Transactors.test(container.getHost, container.getMappedPort(5432), user, pass))
      .evalTap(xas => Transactors.dropAndCreateDDLs.flatMap(xas.execDDLs))
  }

  def resource(
      user: String = PostgresUser,
      pass: String = PostgresPassword
  ): Resource[IO, Transactors] =
    transactors(PostgresContainer.resource(user, pass), user, pass)

  trait Fixture { self: NexusSuite =>
    val doobie: catseffect.IOFixture[Transactors] =
      ResourceSuiteLocalFixture("doobie", resource(PostgresUser, PostgresPassword))

    /**
      * Init the partition in the events and states table for the given projects
      */
    def initPartitions(xas: Transactors, projects: ProjectRef*): IO[Unit] =
      projects
        .traverse { project =>
          val partitionInit = Execute(project)
          partitionInit.initializePartition("scoped_events") >> partitionInit.initializePartition("scoped_states")
        }
        .transact(xas.write)
        .void
  }

  trait Assertions { self: munit.Assertions =>
    implicit class DoobieCatsAssertionsOps[A](io: IO[A])(implicit loc: Location) {
      def expectUniqueViolation: IO[Unit] = io.attempt.map {
        case Left(p: PSQLException) if p.getSQLState == sqlstate.class23.UNIQUE_VIOLATION.value => ()
        case Left(p: PSQLException)                                                             =>
          fail(
            s"Wrong sql state caught, expected: '${sqlstate.class23.UNIQUE_VIOLATION.value}', actual: '${p.getSQLState}' "
          )
        case Left(err)                                                                          =>
          fail(s"Wrong raised error type caught, expected: 'PSQLException', actual: '${err.getClass.getName}'")
        case Right(a)                                                                           =>
          fail(s"Expected raising error, but returned successful response with value '$a'")
      }
    }
  }
}
