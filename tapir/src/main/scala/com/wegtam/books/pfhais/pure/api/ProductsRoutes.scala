/*
 * CC0 1.0 Universal (CC0 1.0) - Public Domain Dedication
 *
 *                                No Copyright
 *
 * The person who associated a work with this deed has dedicated the work to
 * the public domain by waiving all of his or her rights to the work worldwide
 * under copyright law, including all related and neighboring rights, to the
 * extent allowed by law.
 */

package com.wegtam.books.pfhais.pure.api

import cats.data._
import cats.effect._
import cats.implicits._
import com.wegtam.books.pfhais.pure.db._
import com.wegtam.books.pfhais.pure.models._
import eu.timepit.refined.auto._
import fs2.Stream
import io.circe.syntax._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl._
import tapir._
import tapir.json.circe._
import tapir.model.{ StatusCode, StatusCodes }
import tapir.server.http4s._

final class ProductsRoutes[F[_]: Sync: ContextShift](repo: Repository[F]) extends Http4sDsl[F] {
  implicit def decodeProduct: EntityDecoder[F, Product] = jsonOf

  val routes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "products" =>
      val prefix = Stream.eval("[".pure[F])
      val suffix = Stream.eval("]".pure[F])
      val ps = repo.loadProducts
        .groupAdjacentBy(_._1)
        .map {
          case (id, rows) => Product.fromDatabase(rows.toList)
        }
        .collect {
          case Some(p) => p
        }
        .map(_.asJson.noSpaces)
        .intersperse(",")
      @SuppressWarnings(Array("org.wartremover.warts.Any"))
      val result: Stream[F, String] = prefix ++ ps ++ suffix
      Ok(result)
    case req @ POST -> Root / "products" =>
      req
        .as[Product]
        .flatMap { p =>
          for {
            cnt <- repo.saveProduct(p)
            res <- cnt match {
              case 0 => InternalServerError()
              case _ => NoContent()
            }
          } yield res
        }
        .handleErrorWith {
          case InvalidMessageBodyFailure(_, _) => BadRequest()
        }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Any"))
  lazy val getRoute: HttpRoutes[F] = ProductsRoutes.getProducts.toRoutes {
    val prefix = Stream.eval("[".pure[F])
    val suffix = Stream.eval("]".pure[F])
    val ps = repo.loadProducts
      .groupAdjacentBy(_._1)
      .map {
        case (id, rows) => Product.fromDatabase(rows.toList)
      }
      .collect {
        case Some(p) => p
      }
      .map(_.asJson.noSpaces)
      .intersperse(",")
    val result: Stream[F, String] = prefix ++ ps ++ suffix
    val bytes: Stream[F, Byte]    = result.through(fs2.text.utf8Encode)
    ???
  }

  val createRoute: HttpRoutes[F] = ProductsRoutes.createProduct.toRoutes { product =>
    for {
      cnt <- repo.saveProduct(product)
      res = cnt match {
        case 0 => StatusCodes.InternalServerError.asLeft[Unit]
        case _ => ().asRight[StatusCode]
      }
    } yield res
  }

}

@SuppressWarnings(Array("org.wartremover.warts.Any"))
object ProductsRoutes {
  val examples = NonEmptyList.one(
      Product(
        id = java.util.UUID.randomUUID,
        names = NonEmptySet.one(
            Translation(
              lang = "de",
              name = "Das ist ein Name."
            )
          ) ++
          NonEmptySet.one(
            Translation(
              lang = "en",
              name = "That's a name."
            )
          ) ++
          NonEmptySet.one(
            Translation(
              lang = "es",
              name = "Ese es un nombre."
            )
          )
      )
    ) :::
    NonEmptyList.one(
      Product(
        id = java.util.UUID.randomUUID,
        names = NonEmptySet.one(
            Translation(
              lang = "de",
              name = "Das sind nicht die Droiden, nach denen sie suchen!"
            )
          ) ++
          NonEmptySet.one(
            Translation(
              lang = "en",
              name = "These are not the droids you're looking for!"
            )
          )
      )
    )

  def getProducts[F[_]]: Endpoint[Unit, StatusCode, Stream[F, Byte], Stream[F, Byte]] =
    endpoint.get
      .in("products")
      .errorOut(statusCode)
      .out(
        streamBody[Stream[F, Byte]](schemaFor[Byte], tapir.MediaType.Json())
          .example(examples.toList.asJson.spaces2)
      )
      .description("Return all existing products in JSON format as a stream of bytes.")

  val createProduct: Endpoint[Product, StatusCode, Unit, Nothing] =
    endpoint.post
      .in("products")
      .in(
        jsonBody[Product]
          .description("The product data which should be created.")
          .example(examples.head)
      )
      .errorOut(statusCode)
      .out(
        statusCode(StatusCodes.NoContent)
          .description("Upon successful product creation no content is returned.")
      )
      .description(
        "Creates a new product. The product data has to be passed encoded as JSON in the request body. If the product creation failes then a HTTP 500 error is returned."
      )

}
