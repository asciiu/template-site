package controllers

// externals
import com.github.t3hnar.bcrypt.Password
import java.time.OffsetDateTime
import javax.inject.{Inject, Singleton}

import jp.t2v.lab.play2.auth._
import play.api.Logger
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.{I18nSupport, Messages, MessagesApi}
import play.api.mvc.{Action, Controller, RequestHeader}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

// internals
import models.account.MailTokenUser
import models.db.{AccountRole, Tables}
import models.User
import models.FormData
import services.{DBService, MailService, MailTokenUserService}
import utils.db.TetraoPostgresDriver.api._
import utils.Mailer

@Singleton
class Authentication @Inject()(val database: DBService,
                               val messagesApi: MessagesApi,
                               val mailService: MailService,
                               val formData: FormData,
                               implicit val webJarAssets: WebJarAssets) extends Controller with AuthConfigTrait with OptionalAuthElement with LoginLogout with I18nSupport {

  implicit val ms = mailService
  val tokenService = new MailTokenUserService()
  def notFoundDefault(implicit request: RequestHeader) = Future.successful(NotFound(views.html.errors.notFound(request)))

  def prepareLogin() = StackAction { implicit request =>
    if (loggedIn.isDefined) {
      Redirect(routes.RestrictedApplication.messages())
    } else {
      Ok(views.html.auth.login(formData.login))
    }
  }

  def logout = Action.async { implicit request =>
    gotoLogoutSucceeded
  }

  def login() = Action.async { implicit request =>
    formData.login.bindFromRequest.fold(
      formWithErrors => {
        println(formWithErrors)
        Future.successful(BadRequest(views.html.auth.login(formWithErrors)))
      },
      account => {
        val q = Tables.Account.filter { row =>
          row.email === account.email && row.emailConfirmed
        }

        database.runAsync(q.result.headOption).flatMap {
          case Some(user) if (account.password.isBcrypted(user.password)) => {
            Logger.info(s"Login by ${account.email} ${user}")
            gotoLoginSucceeded(user.id)
          }
          case _ => {
            Logger.warn(s"Failed login by ${account.email}")
            val form = formData.login.fill(account).withGlobalError("Invalid email/password")
            Future.successful(BadRequest(views.html.auth.login(form)))
          }
        }
      }
    )
  }

  /**
    * Starts the sign up mechanism. It shows a form that the user have to fill in and submit.
    */
  def signup() = StackAction { implicit request =>
    Ok(views.html.auth.signup(formData.addAccount))
  }

  /**
    * Handles the form filled by the user. The user and its password are saved and it sends him an email
    * with a link to verify his email address.
    */
  def handleSignUp() = Action.async { implicit request =>
    formData.addAccount.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(views.html.auth.signup(formWithErrors))),
      accountFormData => {
        if(accountFormData.password.nonEmpty && accountFormData.password == accountFormData.passwordAgain) {
          val now = OffsetDateTime.now()
          val row = Tables.AccountRow(
            id = -1,
            name = accountFormData.name,
            email = accountFormData.email,
            emailConfirmed = false,
            password = accountFormData.password.bcrypt,
            role = AccountRole.normal,
            updatedAt = now,
            createdAt = now
          )

          for {
            id <- database.runAsync((Tables.Account returning Tables.Account.map(_.id)) += row)
            token <- tokenService.create(MailTokenUser(accountFormData.email, isSignUp = true))
          } yield {
            val user = User(Some(id), accountFormData.name, accountFormData.email, false, accountFormData.password.bcrypt)
            Mailer.welcome(user, link = routes.Authentication.verifySignUp(token.get.id).absoluteURL())
            Ok(views.html.auth.almostSignedUp(accountFormData))
          }
        } else {
          val form = formData.addAccount.fill(accountFormData).withError("passwordAgain", "Passwords don't match")
          Future.successful(BadRequest(views.html.auth.signup(form)))
        }
      }
    )
  }

  /**
    * Verifies the user's email address based on the token.
    */
  def verifySignUp(tokenId: String) = Action.async { implicit request =>
    tokenService.retrieve(tokenId).flatMap {
      case Some(token) if (token.isSignUp && !token.isExpired) => {
        tokenService.consume(tokenId)
        // set email confirmed to true for user
        val query = for { u <- Tables.Account if u.email === token.email } yield u.emailConfirmed
        val updateAction = query.update(true)
        val queryId = Tables.Account.filter (_.email === token.email)

        for {
          update <- database.runAsync(updateAction)
          user <- database.runAsync(queryId.result.headOption)
          result <- gotoLoginSucceeded(user.get.id)
        } yield {
          result
        }
      }
      case Some(token) =>
        tokenService.consume(tokenId)
        Future.successful(NotFound(views.html.errors.notFound(request)))
      case _ =>
        Future.successful(NotFound(views.html.errors.notFound(request)))
    }
  }

  val emailForm = Form(single("email" -> email))
  /**
    * Starts the reset password mechanism if the user has forgot his password. It shows a form to insert his email address.
    */
  def forgotPassword = Action.async { implicit request =>
    Future.successful(Ok(views.html.auth.forgotPassword(emailForm)))
    //Future.successful(request.identity match {
    //  case Some(_) => Redirect(routes.Application.index)
    //  case None => Ok(views.html.auth.forgotPassword(emailForm))
    //})
  }

  /**
    * Sends an email to the user with a link to reset the password
    */
  def handleForgotPassword = Action.async { implicit request =>
    emailForm.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(views.html.auth.forgotPassword(formWithErrors))),
      email => {
        val query = Tables.Account.filter (_.email === email)
        database.runAsync(query.result.headOption).flatMap {
          case Some(user) =>
            val token = MailTokenUser(email, isSignUp = false)
            tokenService.create(token).map { _ =>
              Mailer.forgotPassword(email, link = routes.Authentication.resetPassword(token.id).absoluteURL())
              Ok(views.html.auth.forgotPasswordSent(email))
            }
          case None =>
            Future.successful(BadRequest(views.html.auth.forgotPassword(emailForm.withError("email", Messages("auth.user.notexists")))))
        }
      }
    )
  }

  val passwordValidation = nonEmptyText(minLength = 6)
  val resetPasswordForm = Form(tuple(
    "password1" -> passwordValidation,
    "password2" -> nonEmptyText
  ) verifying (Messages("auth.passwords.notequal"), passwords => passwords._2 == passwords._1))

  /**
    * Confirms the user's link based on the token and shows him a form to reset the password
    */
  def resetPassword(tokenId: String) = Action.async { implicit request =>
    tokenService.retrieve(tokenId).flatMap {
      case Some(token) if (!token.isSignUp && !token.isExpired) => {
        Future.successful(Ok(views.html.auth.resetPassword(tokenId, resetPasswordForm)))
      }
      case Some(token) => {
        tokenService.consume(tokenId)
        Future.successful(NotFound(views.html.errors.notFound(request)))
      }
      case None =>
        Future.successful(NotFound(views.html.errors.notFound(request)))
    }
  }

  /**
    * Saves the new password and authenticates the user
    */
  def handleResetPassword(tokenId: String) = Action.async { implicit request =>
    resetPasswordForm.bindFromRequest.fold(
      formWithErrors => Future.successful(BadRequest(views.html.auth.resetPassword(tokenId, formWithErrors))),
      passwords => {
        tokenService.retrieve(tokenId).flatMap {
          case Some(token) if (!token.isSignUp && !token.isExpired) => {
            val query = for { u <- Tables.Account if u.email === token.email } yield (u.emailConfirmed, u.password)
            val updateAction = query.update(true, passwords._1.bcrypt)
            val queryId = Tables.Account.filter (_.email === token.email)

            for {
              update <- database.runAsync(updateAction)
              user <- database.runAsync(queryId.result.headOption)
              result <- gotoLoginSucceeded(user.get.id)
            } yield {
              tokenService.consume(tokenId)
              result
            }
          }
          case Some(token) => {
            tokenService.consume(tokenId)
            notFoundDefault
          }
          case None => notFoundDefault
        }
      }
    )
  }
}
