package com.microsoft.azure.cosmosdb.kafka.connect.source

import java.util

import com.microsoft.azure.cosmosdb._
import com.microsoft.azure.cosmosdb.kafka.connect.CosmosDBProvider
import com.microsoft.azure.cosmosdb.kafka.connect.common.ErrorHandler.HandleRetriableError
import com.microsoft.azure.cosmosdb.rx._
import org.apache.kafka.connect.source.{SourceRecord, SourceTaskContext}

import scala.util.{Failure, Success, Try}
import scala.collection.JavaConversions._

class CosmosDBReader(private val client: AsyncDocumentClient,
                     val setting: CosmosDBSourceSettings,
                     private val context: SourceTaskContext) extends HandleRetriableError {


  private val SOURCE_PARTITION_FIELD = "partition"
  private val SOURCE_OFFSET_FIELD = "continuationToken"
  private var continuationToken: String = getContinuationTokenSeed(setting.assignedPartition)

  def processChanges(): util.List[SourceRecord] = {

    val processingStartTime = System.currentTimeMillis()
    val records = new util.ArrayList[SourceRecord]
    var bufferSize = 0

    val collectionLink = CosmosDBProvider.getCollectionLink(setting.database, setting.collection)
    val changeFeedOptions = createChangeFeedOptions()

    try{
      val changeFeedResultList = client.queryDocumentChangeFeed(collectionLink, changeFeedOptions)
        .toList()
        .toBlocking()
        .single()

      changeFeedResultList.forEach(
      feedResponse => {
        val documents = feedResponse.getResults().map(d => d)
        documents.toList.foreach(doc =>
        {

          continuationToken = doc.get("_lsn").toString

          logger.debug(s"Sending document ${doc} to the Kafka topic ${setting.topicName}")
          logger.debug(s"Partition: ${setting.assignedPartition}, continuationToken: ${continuationToken}")

          bufferSize = bufferSize + doc.toJson().getBytes().length

          records.add(new SourceRecord(
            sourcePartition(setting.assignedPartition),
            sourceOffset(continuationToken),
            setting.topicName,
            null,
            doc.toJson()
          ))

          val processingElapsedTime = System.currentTimeMillis() - processingStartTime

          if (records.size >= setting.batchSize || bufferSize >= setting.bufferSize || processingElapsedTime >= setting.timeout) {
            return records
          }

        })
      }

    )
    }catch{
      case f: Throwable =>
        logger.error(s"Couldn't add documents to the kafka topic: ${f.getMessage}", f)
        logger.error("Collection link", collectionLink)
        logger.error("Change feed options", changeFeedOptions)
        //HandleError(Failure(f))

    }
    return records
  }

  private def createChangeFeedOptions(): ChangeFeedOptions = {
    val changeFeedOptions = new ChangeFeedOptions()
    changeFeedOptions.setPartitionKeyRangeId(setting.assignedPartition)
    changeFeedOptions.setMaxItemCount(setting.batchSize)
    continuationToken match {
      case null => changeFeedOptions.setStartFromBeginning(true)
      case "" => changeFeedOptions.setStartFromBeginning(true)
      case t => changeFeedOptions.setRequestContinuation(t)
    }
    return changeFeedOptions
  }

  private def getContinuationTokenSeed(partition: String): String = {
    var continuationToken: String = null
    if (context != null) {
      val offset = context.offsetStorageReader.offset(sourcePartition(partition))
      if (offset != null) {
        continuationToken = offset.get(SOURCE_OFFSET_FIELD).toString()
      }
    }
    return continuationToken
  }

  private def sourcePartition(partition: String): util.Map[String, String] = {
    val map = new java.util.HashMap[String,String]
    map.put(SOURCE_PARTITION_FIELD, partition)
    return map
  }

  private def sourceOffset(offset: String): util.Map[String, String] = {
    val map = new java.util.HashMap[String,String]
    map.put(SOURCE_OFFSET_FIELD, offset)
    return map
  }
}