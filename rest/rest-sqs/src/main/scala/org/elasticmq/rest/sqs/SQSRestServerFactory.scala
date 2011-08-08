package org.elasticmq.rest.sqs

import org.elasticmq.rest.RequestHandlerBuilder._
import org.elasticmq.rest.RestPath._
import org.jboss.netty.handler.codec.http.HttpMethod
import org.elasticmq.rest.{StringResponse, RestServer}
import org.elasticmq.{MillisVisibilityTimeout, Queue, Client}

import HttpMethod._
import SQSLogic._
import xml.Elem

class SQSRestServerFactory(client: Client, port: Int, baseAddress: String) {
  import SQSUtil._
  import SQSConstants._

  val defaultVisibilityTimeout = 30 * 1000L;

  val queueURLPath = "queue"

  val actionParameter = "Action"
  val defaultVisibilityTimeoutParameter = "DefaultVisibilityTimeout"

  val createQueueAction = actionParameter -> "CreateQueue"
  val deleteQueueAction = actionParameter -> "DeleteQueue"

  val createQueueLogic = logic((queueName, request, parameters) => {
    val queueOption = client.queueClient.lookupQueue(queueName)

    val visibilityTimeout = parameters.parseOptionalLong(defaultVisibilityTimeoutParameter)
            .getOrElse(defaultVisibilityTimeout)

    val queue = queueOption.getOrElse(client.queueClient.createQueue(
      Queue(queueName, MillisVisibilityTimeout(visibilityTimeout))))

    if (queue.defaultVisibilityTimeout.millis != visibilityTimeout) {
      // Special case: the queue existed, but has a different visibility timeout
      throw new SQSException("AWS.SimpleQueueService.QueueNameExists")
    }

    <CreateQueueResponse xmlns="http://queue.amazonaws.com/doc/2009-02-01/">
      <CreateQueueResult>
        <QueueUrl>{baseAddress}/{queueURLPath}/{queueName}</QueueUrl>
      </CreateQueueResult>
      <ResponseMetadata>
        <RequestId>{emptyRequestId}</RequestId>
      </ResponseMetadata>
    </CreateQueueResponse>
  })

  val deleteQueueLogic = logic((queueName, request, parameters) => {
    val queue = getQueue(queueName)
    client.queueClient.deleteQueue(queue)

    <DeleteQueueResponse xmlns="http://queue.amazonaws.com/doc/2009-02-01/">
      <ResponseMetadata>
        <RequestId>{emptyRequestId}</RequestId>
      </ResponseMetadata>
    </DeleteQueueResponse>
  })

  def getQueue(queueName: String) = {
    val queueOption = client.queueClient.lookupQueue(queueName)

    queueOption match {
      case Some(q) => q
      case None => throw new SQSException("AWS.SimpleQueueService.NonExistentQueue")
    }
  }

  val createQueueGetHandler = (createHandler
            forMethod GET
            forPath (root)
            requiringParameters List(queueNameParameter)
            requiringParameterValues Map(createQueueAction)
            running createQueueLogic)

  val createQueuePostHandler = (createHandler
            forMethod POST
            forPath (root)
            includingParametersFromBody ()
            requiringParameters List(queueNameParameter)
            requiringParameterValues Map(createQueueAction)
            running createQueueLogic)

  val deleteQueueGetHandler = (createHandler
            forMethod GET
            forPath (root / queueURLPath / %("QueueName"))
            requiringParameterValues Map(deleteQueueAction)
            running deleteQueueLogic)

  def start(): RestServer = {
    RestServer.start(
      createQueueGetHandler :: createQueuePostHandler ::
              deleteQueueGetHandler ::
              Nil, port)
  }
}

object SQSConstants {
  val emptyRequestId = "00000000-0000-0000-0000-000000000000"

  val queueNameParameter = "QueueName"
}

object SQSUtil {
  class ParametersParser(parameters: Map[String, String]) {
    def parseOptionalLong(name: String) = {
      val param = parameters.get(name)
      try {
        param.map(_.toLong)
      } catch {
        case e: NumberFormatException => throw new SQSException("InvalidParameterValue")
      }
    }
  }

  implicit def mapToParametersParser(parameters: Map[String, String]): ParametersParser = new ParametersParser(parameters)

  implicit def elemToStringResponse(e: Elem): StringResponse = StringResponse(e.toString())
}