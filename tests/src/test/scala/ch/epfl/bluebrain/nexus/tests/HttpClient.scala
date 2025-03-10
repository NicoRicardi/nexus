package ch.epfl.bluebrain.nexus.tests

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpCharsets._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.Multipart.FormData
import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`Accept-Encoding`, Accept, Authorization, HttpEncodings}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.http.scaladsl.{Http, HttpExt}
import akka.stream.Materializer
import akka.stream.alpakka.sse.scaladsl.EventSource
import akka.stream.scaladsl.Sink
import cats.effect.IO
import cats.effect.unsafe.implicits._
import ch.epfl.bluebrain.nexus.tests.HttpClient.{jsonHeaders, rdfApplicationSqlQuery, tokensMap}
import ch.epfl.bluebrain.nexus.tests.Identity.Anonymous
import io.circe.Json
import io.circe.parser._
import fs2._
import org.scalatest.matchers.should.Matchers
import org.scalatest.{AppendedClues, Assertion}

import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class HttpClient private (baseUrl: Uri, httpExt: HttpExt)(implicit
    as: ActorSystem,
    materializer: Materializer,
    ec: ExecutionContext
) extends Matchers
    with AppendedClues {

  private def fromFuture[A](future: => Future[A]) = IO.fromFuture { IO.delay(future) }

  def apply(req: HttpRequest): IO[HttpResponse] =
    fromFuture(httpExt.singleRequest(req))

  def head(url: Uri, identity: Identity)(assertResponse: HttpResponse => Assertion): IO[Assertion] = {
    val req = HttpRequest(HEAD, s"$baseUrl$url", headers = identityHeader(identity).toList)
    fromFuture(httpExt.singleRequest(req)).map(assertResponse)
  }

  def run[A](req: HttpRequest)(implicit um: FromEntityUnmarshaller[A]): IO[(A, HttpResponse)] =
    fromFuture(httpExt.singleRequest(req)).flatMap { res =>
      fromFuture(um.apply(res.entity)).map(a => (a, res))
    }

  def post[A](url: String, body: Json, identity: Identity, extraHeaders: Seq[HttpHeader] = jsonHeaders)(
      assertResponse: (A, HttpResponse) => Assertion
  )(implicit um: FromEntityUnmarshaller[A]): IO[Assertion] =
    requestAssert(POST, url, Some(body), identity, extraHeaders)(assertResponse)

  def postIO[A](url: String, body: IO[Json], identity: Identity, extraHeaders: Seq[HttpHeader] = jsonHeaders)(
      assertResponse: (A, HttpResponse) => Assertion
  )(implicit um: FromEntityUnmarshaller[A]): IO[Assertion] = {
    body.flatMap(body => requestAssert(POST, url, Some(body), identity, extraHeaders)(assertResponse))
  }

  def put[A](url: String, body: Json, identity: Identity, extraHeaders: Seq[HttpHeader] = jsonHeaders)(
      assertResponse: (A, HttpResponse) => Assertion
  )(implicit um: FromEntityUnmarshaller[A]): IO[Assertion] =
    requestAssert(PUT, url, Some(body), identity, extraHeaders)(assertResponse)

  def putIO[A](url: String, body: IO[Json], identity: Identity, extraHeaders: Seq[HttpHeader] = jsonHeaders)(
      assertResponse: (A, HttpResponse) => Assertion
  )(implicit um: FromEntityUnmarshaller[A]): IO[Assertion] = {
    body.flatMap(body => requestAssert(PUT, url, Some(body), identity, extraHeaders)(assertResponse))
  }

  def putAttachmentFromPath[A](
      url: String,
      path: Path,
      contentType: ContentType,
      fileName: String,
      identity: Identity,
      extraHeaders: Seq[HttpHeader] = jsonHeaders
  )(assertResponse: (A, HttpResponse) => Assertion)(implicit um: FromEntityUnmarshaller[A]): IO[Assertion] = {
    request(
      PUT,
      url,
      Some(path),
      identity,
      (p: Path) => {
        val entity = HttpEntity(contentType, Files.readAllBytes(p))
        FormData(BodyPart.Strict("file", entity, Map("filename" -> fileName))).toEntity()
      },
      assertResponse,
      extraHeaders
    )
  }

  def uploadFile[A](
      url: String,
      attachment: String,
      contentType: ContentType,
      fileName: String,
      identity: Identity,
      extraHeaders: Seq[HttpHeader] = jsonHeaders
  )(assertResponse: (A, HttpResponse) => Assertion)(implicit um: FromEntityUnmarshaller[A]): IO[Assertion] = {
    def buildClue(a: A, response: HttpResponse) =
      s"""
         |Endpoint: PUT $url
         |Identity: $identity
         |Token: ${Option(tokensMap.get(identity)).map(_.credentials.token()).getOrElse("None")}
         |Status code: ${response.status}
         |Body: None
         |Response:
         |$a
         |""".stripMargin

    request(
      PUT,
      url,
      Some(attachment),
      identity,
      (s: String) => {
        val entity = HttpEntity(contentType, s.getBytes)
        FormData(BodyPart.Strict("file", entity, Map("filename" -> fileName))).toEntity()
      },
      (a: A, response: HttpResponse) => assertResponse(a, response) withClue buildClue(a, response),
      extraHeaders
    )
  }

  def patch[A](url: String, body: Json, identity: Identity, extraHeaders: Seq[HttpHeader] = jsonHeaders)(
      assertResponse: (A, HttpResponse) => Assertion
  )(implicit um: FromEntityUnmarshaller[A]): IO[Assertion] =
    requestAssert(PATCH, url, Some(body), identity, extraHeaders)(assertResponse)

  def getWithBody[A](url: String, body: Json, identity: Identity, extraHeaders: Seq[HttpHeader] = jsonHeaders)(
      assertResponse: (A, HttpResponse) => Assertion
  )(implicit um: FromEntityUnmarshaller[A]): IO[Assertion] =
    requestAssert(GET, url, Some(body), identity, extraHeaders)(assertResponse)

  def get[A](url: String, identity: Identity, extraHeaders: Seq[HttpHeader] = jsonHeaders)(
      assertResponse: (A, HttpResponse) => Assertion
  )(implicit um: FromEntityUnmarshaller[A]): IO[Assertion] =
    requestAssert(GET, url, None, identity, extraHeaders)(assertResponse)

  def getJson[A](url: String, identity: Identity)(implicit um: FromEntityUnmarshaller[A]): IO[A] = {
    requestJson(GET, url, None, identity, (a: A, _: HttpResponse) => a, jsonHeaders)
  }

  def delete[A](url: String, identity: Identity, extraHeaders: Seq[HttpHeader] = jsonHeaders)(
      assertResponse: (A, HttpResponse) => Assertion
  )(implicit um: FromEntityUnmarshaller[A]): IO[Assertion] =
    requestAssert(DELETE, url, None, identity, extraHeaders)(assertResponse)

  def requestAssert[A](
      method: HttpMethod,
      url: String,
      body: Option[Json],
      identity: Identity,
      extraHeaders: Seq[HttpHeader] = jsonHeaders
  )(assertResponse: (A, HttpResponse) => Assertion)(implicit um: FromEntityUnmarshaller[A]): IO[Assertion] = {
    def buildClue(a: A, response: HttpResponse) =
      s"""
        |Endpoint: ${method.value} $url
        |Identity: $identity
        |Token: ${Option(tokensMap.get(identity)).map(_.credentials.token()).getOrElse("None")}
        |Status code: ${response.status}
        |Body: ${body.getOrElse("None")}
        |Response:
        |$a
        |""".stripMargin

    requestJson(
      method,
      url,
      body,
      identity,
      (a: A, response: HttpResponse) => assertResponse(a, response) withClue buildClue(a, response),
      extraHeaders
    )
  }

  def sparqlQuery[A](url: String, query: String, identity: Identity, extraHeaders: Seq[HttpHeader] = Nil)(
      assertResponse: (A, HttpResponse) => Assertion
  )(implicit um: FromEntityUnmarshaller[A]): IO[Assertion] = {
    request(
      POST,
      url,
      Some(query),
      identity,
      (s: String) => HttpEntity(rdfApplicationSqlQuery, s),
      assertResponse,
      extraHeaders
    )
  }

  def requestJson[A, R](
      method: HttpMethod,
      url: String,
      body: Option[Json],
      identity: Identity,
      f: (A, HttpResponse) => R,
      extraHeaders: Seq[HttpHeader]
  )(implicit um: FromEntityUnmarshaller[A]): IO[R] =
    request(
      method,
      url,
      body,
      identity,
      (j: Json) => HttpEntity(ContentTypes.`application/json`, j.noSpaces),
      f,
      extraHeaders
    )

  private def identityHeader(identity: Identity): Option[HttpHeader] = {
    identity match {
      case Anonymous => None
      case _         =>
        Some(
          Option(tokensMap.get(identity)).getOrElse(
            throw new IllegalArgumentException(
              "The provided user has not been properly initialized, please add it to Identity.allUsers."
            )
          )
        )
    }
  }

  def request[A, B, R](
      method: HttpMethod,
      url: String,
      body: Option[B],
      identity: Identity,
      toEntity: B => HttpEntity.Strict,
      f: (A, HttpResponse) => R,
      extraHeaders: Seq[HttpHeader]
  )(implicit um: FromEntityUnmarshaller[A]): IO[R] =
    apply(
      HttpRequest(
        method = method,
        uri = s"$baseUrl$url",
        headers = extraHeaders ++ identityHeader(identity),
        entity = body.fold(HttpEntity.Empty)(toEntity)
      )
    ).flatMap { res =>
      fromFuture { um(res.entity) }
        .map { f(_, res) }
    }

  def stream[A, B](
      url: String,
      nextUrl: A => Option[String],
      lens: A => B,
      identity: Identity,
      extraHeaders: Seq[HttpHeader] = jsonHeaders
  )(implicit um: FromEntityUnmarshaller[A]): Stream[IO, B] = {
    Stream.unfoldLoopEval[IO, String, B](url) { currentUrl =>
      requestJson[A, A](
        GET,
        currentUrl,
        None,
        identity,
        (a: A, _: HttpResponse) => a,
        extraHeaders
      ).map { a =>
        (lens(a), nextUrl(a))
      }
    }
  }

  def sseEvents(
      url: String,
      identity: Identity,
      initialLastEventId: Option[String],
      take: Long = 100L,
      takeWithin: FiniteDuration = 5.seconds
  )(assertResponse: Seq[(Option[String], Option[Json])] => Assertion): IO[Assertion] = {
    def send(request: HttpRequest): Future[HttpResponse] =
      apply(request.addHeader(tokensMap.get(identity))).unsafeToFuture()
    fromFuture {
      EventSource(s"$baseUrl$url", send, initialLastEventId = initialLastEventId)
        //drop resolver, views and storage events
        .take(take)
        .takeWithin(takeWithin)
        .runWith(Sink.seq)
    }
      .map { seq =>
        assertResponse(
          seq.map { s =>
            (s.eventType, parse(s.data).toOption)
          }
        )
      }
  }

}

object HttpClient {

  val tokensMap: ConcurrentHashMap[Identity, Authorization] = new ConcurrentHashMap[Identity, Authorization]

  val acceptAll: Seq[Accept] = Seq(Accept(MediaRanges.`*/*`))

  val acceptZip: Seq[Accept] = Seq(Accept(MediaTypes.`application/zip`, MediaTypes.`application/json`))

  val jsonHeaders: Seq[HttpHeader] = Accept(MediaTypes.`application/json`) :: Nil

  val rdfApplicationSqlQuery: MediaType.WithFixedCharset =
    MediaType.applicationWithFixedCharset("sparql-query", `UTF-8`)
  val sparqlQueryHeaders: Seq[HttpHeader]                = Accept(rdfApplicationSqlQuery) :: Nil

  val gzipHeaders: Seq[HttpHeader] = Seq(Accept(MediaRanges.`*/*`), `Accept-Encoding`(HttpEncodings.gzip))

  def apply(baseUrl: Uri)(implicit
      as: ActorSystem,
      materializer: Materializer,
      ec: ExecutionContext
  ) = new HttpClient(baseUrl, Http())
}
