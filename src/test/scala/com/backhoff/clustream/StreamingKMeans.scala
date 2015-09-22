package com.backhoff.clustream

/**
 * Created by omar on 9/18/15.
 */

import org.apache.spark.streaming.scheduler.{StreamingListenerBatchCompleted, StreamingListener}
import org.apache.spark.streaming.{Milliseconds, StreamingContext}
import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.clustering.StreamingKMeans


object StreamingKMeans {
    def main(args: Array[String]) {
    val conf = new SparkConf().setAppName("Streaming K-means test").setMaster("local[*]")
    val sc = new SparkContext(conf)
    sc.setLogLevel("ERROR")
    val ssc = new StreamingContext(sc, Milliseconds(1000))
    val trainingData = ssc.textFileStream("file:///home/omar/stream/train").map(_.split(" ")).map(arr => arr.dropRight(1)).map(_.mkString("[",",","]")).map(Vectors.parse)
    //val trainingData = ssc.socketTextStream("localhost",9999).map(_.split(" ")).map(arr => arr.dropRight(1)).map(_.mkString("[",",","]")).map(Vectors.parse)
    //val testData = ssc.textFileStream("/home/omar/stream/testing").map(LabeledPoint.parse)
    val testData = ssc.socketTextStream("localhost", 9998).map(LabeledPoint.parse)
    val numDimensions = 2
    val numClusters = 2
    val model = new StreamingKMeans()
      .setK(numClusters)
      .setDecayFactor(0.5)
      .setRandomCenters(numDimensions, 0.0)

    val oldCenters = new StaticVar(Array.fill(numClusters)(Array.fill(numDimensions)(0.0)))
    //val old = new StaticVar(Array.fill(numDimensions)(Vectors.dense(Array.fill(numDimensions)(0.0))))
    val listener = new MyListener(model, oldCenters,numDimensions,numClusters)

    ssc.addStreamingListener(listener)

    model.trainOn(trainingData)
    model.predictOnValues(testData.map(lp => (lp.label, lp.features))).print()

    ssc.start()
    ssc.awaitTermination()
  }
}

private[clustream] class MyListener(model:StreamingKMeans, oldCenters:StaticVar[Array[Array[Double]]], numClusters:Int, numDimensions:Int) extends StreamingListener {

  def modelCenters2Array(model:StreamingKMeans, numClusters:Int, numDimensions:Int):Array[Array[Double]]= {
    val arr = Array.fill(numClusters)(Array.fill(numDimensions)(0.0))
    for(i <- model.latestModel().clusterCenters.indices){
      arr(i) = model.latestModel().clusterCenters(i).toArray
    }
    arr
  }
  override def onBatchCompleted(batchCompleted:StreamingListenerBatchCompleted) {
    val centers = modelCenters2Array(model,numClusters,numDimensions)
    if ( !(centers.deep == oldCenters.value.deep) ) {
      println( (if(oldCenters.value(0).deep != Array.fill(numDimensions)(0.0).deep) "(UPDATED) " else "(INITIAL) ")  + "Centers: ")
      centers.foreach(_.mkString("",",","\n").foreach(print))
      for(i <- centers.indices)
        for(j <- centers(i).indices)
         oldCenters.value(i)(j) = centers(i)(j)
    }
  }
}

class StaticVar[T]( val value: T )