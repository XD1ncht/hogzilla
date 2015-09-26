package org.hogzilla.dns

import java.util.HashMap
import java.util.Map
import scala.math.random
import org.apache.hadoop.hbase.util.Bytes
import org.apache.spark._
import org.apache.spark.mllib.clustering.KMeans
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.rdd.RDD
import org.hogzilla.hbase.HogHBaseRDD
import org.hogzilla.event.HogEvent
import java.util.HashSet
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.classification.SVMWithSGD
import scala.tools.nsc.doc.base.comment.OrderedList
import org.apache.spark.mllib.optimization.L1Updater

/**
 * 
References: http://www.zytrax.com/books/dns/ch15/
 */
object HogDNS {

  /**
   * 
   * 
   * 
   */
  def run(HogRDD: RDD[(org.apache.hadoop.hbase.io.ImmutableBytesWritable,org.apache.hadoop.hbase.client.Result)],spark:SparkContext)
  {
    
    // DNS K-means clustering on flow bytes
    //kmeansBytes(HogRDD)
    
    // DNS Super Bag
    //superbag(HogRDD,spark)
    
    // DNS K-means clustering
   kmeans(HogRDD)
 
  }
  
  
  /**
   * 
   * 
   * 
   */
  def populate(event:HogEvent):HogEvent =
  {
    val centroids:String = event.data.get("centroids")
    val vector:String = event.data.get("vector")
    val clusterLabel:String = event.data.get("clusterLabel")
    val hostname:String = event.data.get("hostname")
    
    event.text = "This flow was detected by Hogzilla as an anormal activity.\n"+
                 ""+hostname+"\n"+
                 "Event details:\n"+
                 "Hogzilla module: HogDNS, Method: k-means clustering with k=10\n"+
                 "Centroids:"+centroids+"\n"
                 "Vector: "+vector+"\n"
                 "(cluster,label nDPI): "+clusterLabel+"\n"
                 
    event
  }
  
  
  /**
   * 
   * 
   * 
   */
  def kmeans(HogRDD: RDD[(org.apache.hadoop.hbase.io.ImmutableBytesWritable,org.apache.hadoop.hbase.client.Result)])
  {
    
	  val features = Array("flow:avg_packet_size",
			                   "flow:packets_without_payload",
                         "flow:avg_inter_time",
			                   "flow:flow_duration",
			                   "flow:max_packet_size",
			                   "flow:bytes",
			                   "flow:packets",
			                   "flow:min_packet_size",
			                   "flow:packet_size-0",
			                   "flow:inter_time-0",
		                     "flow:dns_num_queries",
			                   "flow:dns_num_answers",
			                   "flow:dns_ret_code",
			                   "flow:dns_bad_packet",
		                 	   "flow:dns_query_type",
		                 	   "flow:dns_rsp_type")
 
     
    val DnsRDD = HogRDD.
        map { case (id,result) => {
          val map: Map[String,String] = new HashMap[String,String]
              map.put("flow:id",Bytes.toString(id.get).toString())
              HogHBaseRDD.columns.foreach { column => 
                
                val ret = result.getValue(Bytes.toBytes(column.split(":")(0).toString()),Bytes.toBytes(column.split(":")(1).toString()))
                map.put(column, Bytes.toString(ret)) 
        }
          if(map.get("flow:dns_num_queries")==null) map.put("flow:dns_num_queries","0")
          if(map.get("flow:dns_num_answers")==null) map.put("flow:dns_num_answers","0")
          if(map.get("flow:dns_ret_code")==null) map.put("flow:dns_ret_code","0")
          if(map.get("flow:dns_bad_packet")==null) map.put("flow:dns_bad_packet","0")
          if(map.get("flow:dns_query_type")==null) map.put("flow:dns_query_type","0")
          if(map.get("flow:dns_rsp_type")==null) map.put("flow:dns_rsp_type","0")
        map
        }
    }.filter(x => x.get("flow:lower_port").equals("53") && x.get("flow:packets").toDouble.>(1)).cache
      
  val DnsRDDcount = DnsRDD.map(flow => features.map { feature => flow.get(feature).toDouble }).cache()
  
  val numCols = DnsRDDcount.first.length
  val n = DnsRDDcount.count()
  val sums = DnsRDDcount.reduce((a,b) => a.zip(b).map(t => t._1 + t._2))
  val sumSquares = DnsRDDcount.fold(
      new Array[Double](numCols)
  )(
      (a,b) => a.zip(b).map(t => t._1 + t._2*t._2)
      )
      
  val stdevs = sumSquares.zip(sums).map{
      case(sumSq,sum) => math.sqrt(n*sumSq - sum*sum)/n
    }
    
  val means = sums.map(_/n)
      
  def normalize(vector: Vector):Vector = {
    val normArray = (vector.toArray,means,stdevs).zipped.map(
        (value,mean,std) =>
          if(std<=0) (value-mean) else (value-mean)/std)
    return Vectors.dense(normArray)
  }
    

    val labelAndData = DnsRDD.map { flow => 
     val vector = Vectors.dense(features.map { feature => flow.get(feature).toDouble })
    ((flow.get("flow:detected_protocol"), if (flow.get("event:priority_id")!=null && flow.get("event:priority_id").equals("1")) 1 else 0 , flow.get("flow:host_server_name"),flow),normalize(vector))
    }
    
    val data = labelAndData.values.cache()
    val kmeans = new KMeans()
    kmeans.setK(9)
    val model = kmeans.run(data)
    
     val clusterLabel = labelAndData.map({
      case (label,datum) =>
        val cluster = model.predict(datum)
        (cluster,label,datum)
    })
    
  
    val clusterLabelCount = clusterLabel.map({
      case (cluster,label,datum) =>
        val map: Map[(Int,String),(Double,Int)] = new HashMap[(Int,String),(Double,Int)]
        map.put((cluster,label._1),  (label._2.toDouble,1))
        map
    }).reduce((a,b) => { 
      b.keySet().toArray()
      .map { 
        case key: (Int,String) =>  
            if (a.containsKey(key))
            {
              val avg = (a.get(key)._1*a.get(key)._2 + b.get(key)._1*b.get(key)._2)/
                          (a.get(key)._2+b.get(key)._2)
                          
              a.put(key, (avg,a.get(key)._2+b.get(key)._2))
            }else
              a.put(key,b.get(key))
      }
      a
    })
    
    println("######################################################################################")
    println("######################################################################################")
    println("######################################################################################")
    println("######################################################################################")
    println("DNS K-Means Clustering")
    println("Centroids")
    val centroids = ""
    model.clusterCenters.foreach { center => centroids.concat("\n"+center.toString) }
    
    clusterLabelCount.keySet().toArray().foreach { case key:(Int,String) =>  
      val cluster = key._1
      val label = key._2
      val count =clusterLabelCount.get(key)._2.toString
      val avg = clusterLabelCount.get(key)._1.toString
      println(f"Cluster: $cluster%1s\t\tLabel: $label%20s\t\tCount: $count%10s\t\tAvg: $avg%10s")
      }


      val tainted = clusterLabelCount.keySet().toArray().filter({ case (cluster:Int,label:String) => cluster.>(0) }).
                  sortBy ({ case (cluster:Int,label:String) => clusterLabelCount.get((cluster,label))._1.toDouble }).reverse.apply(0)
      
                  
      println("######################################################################################")
      println("Tainted flows of: "+tainted.toString())
      
      clusterLabel.filter({ case (cluster,(group,taited,hostname,flow),datum) => (cluster,group).equals(tainted) }).
      foreach{ case (cluster,(group,taited,hostname,flow),datum) => 
        val event = new HogEvent(flow)
        event.data.put("centroids", centroids)
        event.data.put("vector", datum.toString)
        event.data.put("clusterLabel", "("+cluster.toString()+","+group+")")
        event.data.put("hostname", flow.get("flow:host_server_name"))
        populate(event).alert()
      }

      
   (1 to 9).map{ k => 
      println("######################################################################################")
      println(f"Hosts from cluster $k%1s")
     clusterLabel.filter(_._1.equals(k)).foreach{ case (cluster,label,datum) => 
        print(label._3+"|")      
      }
      println("")
   }

    println("######################################################################################")
    println("######################################################################################")
    println("######################################################################################")
    println("######################################################################################")           


  }
  
  
  
  /**
   * 
   * 
   * 
   */
  def kmeansBytes(HogRDD: RDD[(org.apache.hadoop.hbase.io.ImmutableBytesWritable,org.apache.hadoop.hbase.client.Result)])
  {
 
     
	  val DnsRDD = HogRDD.
			  map { case (id,result) => {
				  val map: Map[String,String] = new HashMap[String,String]
						  map.put("flow:id",Bytes.toString(id.get).toString())
						  HogHBaseRDD.columns.foreach { column => map.put(column, 
								  Bytes.toString(result.getValue(Bytes.toBytes(column.split(":")(0).toString()),Bytes.toBytes(column.split(":")(1).toString())))) 
			  }
			  map
			  }
	  }

   
	  val labelAndData = DnsRDD.filter(_.get("flow:lower_port").equals("53")).map { flow => 
	  
    /*val vector = Vectors.dense(flow.get("flow:avg_packet_size").toDouble,flow.get("flow:bytes").toDouble)
	  ((flow.get("flow:detected_protocol"),flow.get("flow:bytes")),vector)
	 // (flow.get("flow:id"),vector)
	  } */
    
     val vector = Vectors.dense(flow.get("flow:bytes").toDouble)
    ((flow.get("flow:detected_protocol"),flow.get("flow:bytes")),vector)
   // (flow.get("flow:id"),vector)
    }
    
    val data = labelAndData.values.cache()
    val kmeans = new KMeans()
    val model = kmeans.run(data)
    
    val clusterLabelCount = labelAndData.map({
      case (label,datum) =>
        val cluster = model.predict(datum)
        val map: Map[(Int,String),(Double,Int)] = new HashMap[(Int,String),(Double,Int)]
        map.put((cluster,label._1),  (label._2.toDouble,1))
        map
    }).reduce((a,b) => { 
      b.keySet().toArray()
      .map { 
        case key: (Int,String) =>  
            if (a.containsKey(key))
            {
              val avg = (a.get(key)._1*a.get(key)._2 + b.get(key)._1*b.get(key)._2)/
                          (a.get(key)._2+b.get(key)._2)
                          
              a.put(key, (avg,a.get(key)._2+b.get(key)._2))
            }else
              a.put(key,b.get(key))
      }
      a
    })
    
    //.countByValue
    
    println("######################################################################################")
    println("DNS K-Means Clustering by Flow Total Bytes")
    println("Centroids")
    model.clusterCenters.foreach { println }
    
    clusterLabelCount.keySet().toArray().foreach { case key:(Int,String) =>  
      val cluster = key._1
      val label = key._2
      val count =clusterLabelCount.get(key)._2.toString
      val avg = clusterLabelCount.get(key)._1.toString
      println(f"Cluster: $cluster%1s\t\tLabel: $label%20s\t\tCount: $count%10s\t\tAvg: $avg%10s")
      }

    /*clusterLabelCount.toSeq.sorted.foreach {
     
      case ((cluster,label),count) =>
        println(f"Cluster: $cluster%1sLabel: $label%18s Count: $count%8s")
    }*/
    
    //val max = hBaseRDD.map(tuple => tuple._2)
    //          .map(result => Bytes.toString(result.getValue(Bytes.toBytes("flow"),Bytes.toBytes("packets"))).toLong)
    //          .reduce((a,b) => Math.max(a,b))
              
    //println("Maximo: "+teste)
  //  teste.toSeq.sortBy(_._2).foreach(println)
    //teste.foreach(println)
    println("######################################################################################")           


  }
  
  
  
  /**
   * 
   * 
   * 
   */
  def superbag(HogRDD: RDD[(org.apache.hadoop.hbase.io.ImmutableBytesWritable,org.apache.hadoop.hbase.client.Result)],spark:SparkContext)
  {

    class flowSet(flowc:Map[String,String]) extends Serializable
    {
      val flows=new HashMap[(String,String,String),(HashSet[Map[String,String]],HashMap[String,Double],LabeledPoint)]
      // (flow:lip,flow:uip,flow:host) -> (Set[flows],Info,LabeledPoint)
      
      add(flowc)

       def add(flow:Map[String,String]) 
      {
        // Add flow in the Set
        val value = flows.get((flow.get("flow:lower_name"),flow.get("flow:upper_name"),flow.get("flow:host_server_name")))
        
        if(value == null)
        {
          val a = new HashSet[Map[String,String]]
          val b = new HashMap[String,Double]
          a.add(flow)
          flows.put((flow.get("flow:lower_name"),flow.get("flow:upper_name"),flow.get("flow:host_server_name")), (a,b,new LabeledPoint(0,Vectors.dense(0))))
        }else
        {
          value._1.add(flow)
        }
      } 
      
      def merge(flowset:flowSet):flowSet =
      {
        val iter = flowset.flows.keySet().toArray().map({ case key:(String,String,String) => 
                      val value = this.flows.get(key)
                      if( value == null)
                      {
                           val a = new HashSet[Map[String,String]]
                           val b = new HashMap[String,Double]
                           a.addAll(flowset.flows.get(key)._1)
                           this.flows.put(key, (a,b,new LabeledPoint(0,Vectors.dense(0)) ))
                      }else
                      {
                        this.flows.get(key)._1.addAll(flowset.flows.get(key)._1)
                      }
                   })
          this
          /*
          val sum = this.flows.get(key)._2.get("qtd") + flowset.flows.get(key)._2.get("qtd")
          this.flows.get(key)._2.put("avg",
              (this.flows.get(key)._2.get("avg")*this.flows.get(key)._2.get("qtd")
                  +
              flowset.flows.get(key)._2.get("avg")*flowset.flows.get(key)._2.get("qtd"))/
              sum
          )
          
          this.flows.get(key)._2.put("qtd",sum)
        */
      }
    }
    
    // Populate flowSet
    val DnsRDD = HogRDD.
        map { case (id,result) => {
          val map: Map[String,String] = new HashMap[String,String]
              map.put("flow:id",Bytes.toString(id.get).toString())
              HogHBaseRDD.columns.foreach { column => 
                
                val ret = result.getValue(Bytes.toBytes(column.split(":")(0).toString()),Bytes.toBytes(column.split(":")(1).toString()))
                map.put(column, Bytes.toString(ret)) 
        }
          if(map.get("flow:dns_num_queries")==null) map.put("flow:dns_num_queries","0")
          if(map.get("flow:dns_num_answers")==null) map.put("flow:dns_num_answers","0")
          if(map.get("flow:dns_ret_code")==null) map.put("flow:dns_ret_code","0")
          if(map.get("flow:dns_bad_packet")==null) map.put("flow:dns_bad_packet","0")
          if(map.get("flow:dns_query_type")==null) map.put("flow:dns_query_type","0")
          if(map.get("flow:dns_rsp_type")==null) map.put("flow:dns_rsp_type","0")
        map
        }
    }.filter(x => x.get("flow:lower_port").equals("53") && x.get("flow:packets").toDouble.>(1)).cache
      
    val superBag = DnsRDD.map({flow => new flowSet(flow) }).reduce((a,b) => a.merge(b)) 
    
    // compute sizes, inter times, means and stddevs
    val labeledpoints:HashSet[LabeledPoint] = new HashSet()
    superBag.flows.keySet().toArray().map({ key =>  superBag.flows.get(key)}).map({case (flowSet1:HashSet[Map[String,String]],info:HashMap[String,Double],labeledpoint:LabeledPoint) => 
      
        if(flowSet1.size()>4)
        {
      
        val flowSetOrdered = flowSet1.toArray().toSeq.map({  case f:Map[String,String] => f }).sortBy(f => f.get("flow:first_seen").toDouble).toArray
                
        val timeArray:Array[Double] = Array.fill[Double](flowSetOrdered.length)(0)
        var dirty:Double =0;
        var clean:Double =0;
        
        (0 to (flowSetOrdered.length-2)).map({ k => 
             timeArray.update(k, flowSetOrdered(k+1).get("flow:first_seen").toDouble - flowSetOrdered(k).get("flow:first_seen").toDouble)
             
             
             if (flowSetOrdered(k).get("event:priority_id")!=null)
             {
               //println(flowSetOrdered(k).get("event:priority_id"))
                dirty=1;
             }
             
             
             if (flowSetOrdered(k).get("flow:detected_protocol").ne("5/DNS"))
             {
                clean=1;
             }
        })
        
        if(dirty==1 || clean==1)
        {
          val count = timeArray.length
          val avg = timeArray.sum/count
          val devs = timeArray.toSeq.map { t => (t-avg)*(t-avg) }
          val stddev = Math.sqrt(devs.sum/count)
        
          info.put("flow_intertime_avg", avg)
          info.put("flow_intertime_stddev", stddev)
          info.put("flow_intertime_count", count)
          
          if(dirty==1)
          {
            println(LabeledPoint(1,Vectors.dense(count,avg,stddev)).toString)
            //labeledpoints.add(LabeledPoint(1,Vectors.dense(count,avg,stddev)))
            info.put("dirty", 1)
            labeledpoints.add(LabeledPoint(1,Vectors.dense(stddev)))
          }else{
            println(LabeledPoint(0,Vectors.dense(count,avg,stddev)).toString)
            info.put("dirty", 0)
            //labeledpoints.add(LabeledPoint(0,Vectors.dense(count,avg,stddev)))
            labeledpoints.add(LabeledPoint(0,Vectors.dense(stddev)))
          }
        }
        
        }
    })
  
    val arraylabeledpoints = labeledpoints.toArray().map({ case xy:LabeledPoint => xy })
   
    // SVM considering dirty flows from Snort
    
    val svmAlg = new SVMWithSGD()
    svmAlg.optimizer.setNumIterations(200).setRegParam(0.1).setUpdater(new L1Updater)
    
    val model = svmAlg.run(spark.parallelize(arraylabeledpoints).cache)
    
    model.clearThreshold()
    
   println("######################################################################################")           
   
   println("Model.intercept: "+model.intercept+" Model.weights: "+model.weights)
   
    superBag.flows.keySet().toArray().map({ case key:(String,String,String) =>  
    
        if(superBag.flows.get(key)._1.size()>4)
        {
      
      val info = superBag.flows.get(key)._2
      
     // val score = model.predict(Vectors.dense(info.get("flow_intertime_count"),info.get("flow_intertime_avg"),info.get("flow_intertime_stddev")) )
      val score = model.predict(Vectors.dense(info.get("flow_intertime_stddev")) )
    // print(score+"|")
     
     if(info.get("flow_intertime_avg")<100000 && info.get("dirty")!=null)
     println("USED: "+key._1+" <-> "+key._2+", hostname: "+key._3+" ("+info.get("flow_intertime_count").toString+","+info.get("flow_intertime_avg").toString+","+info.get("flow_intertime_stddev").toString+")")
            
     if(score>0)
     {
     //  println("Tainted flow: "+key._1+" <-> "+key._2+", hostname: "+key._3+"")
       println("Tainted flow: "+key._1+" <-> "+key._2+", hostname: "+key._3+" ("+info.get("flow_intertime_count").toString+","+info.get("flow_intertime_avg").toString+","+info.get("flow_intertime_stddev").toString+")")
     }
        }
     })
     
    println("######################################################################################")           
 
    // Taint the dirty side, generating HogEvents
    /* */

  }
  
}