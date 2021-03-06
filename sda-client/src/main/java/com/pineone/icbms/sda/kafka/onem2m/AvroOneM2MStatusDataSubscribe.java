package com.pineone.icbms.sda.kafka.onem2m;

import java.io.Serializable;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.message.MessageAndMetadata;

import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.pineone.icbms.sda.comm.util.Utils;
import com.pineone.icbms.sda.comm.SchComm;
import com.pineone.icbms.sda.comm.kafka.avro.COL_ONEM2M;

import com.pineone.icbms.sda.sf.TripleService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
//import java.util.concurrent.atomic.AtomicInteger;

// thread로 돌리는 경우
public class AvroOneM2MStatusDataSubscribe implements Serializable  {
	private static final long serialVersionUID = -327396803601587403L;
	private final String TOPIC = Utils.KafkaTopics.COL_ONEM2M.toString();
	private final Log log = LogFactory.getLog(AvroOneM2MStatusDataSubscribe.class);
	
	private final TripleService tripleService = new TripleService();	
	private final int NUM_THREADS = Integer.parseInt(Utils.getSdaProperty ("com.pineone.icbms.sda.kafka.thread.count"));
	
	private final String user_id =this.getClass().getName();
	private final String group_id = this.getClass().getSimpleName();
	
	public static void main(String[] args) throws Exception{
		AvroOneM2MStatusDataSubscribe avroOneM2MStatusDataSubscribe = new AvroOneM2MStatusDataSubscribe();
		avroOneM2MStatusDataSubscribe.collect();
	}

	public void collect() throws Exception{
		Properties properties = new Properties();
		
		//class name을 user_id, grup_id로 사용함
		properties.put("zookeeper.connect",Utils.ZOOKEEPER_LIST);
		properties.put("group.id",group_id);
		properties.put("zookeeper.session.timeout.ms", "500");
		properties.put("zookeeper.sync.time.ms", "250");
		//properties.put("auto.commit.enable", "false");
		properties.put("auto.commit.enable", "true");
		properties.put("auto.commit.interval.ms", "60000");
		properties.put("fetch.message.max.bytes", "31457280");		// 30MB		
		properties.put("auto.offset.reset", "smallest");
		//properties.put("auto.offset.reset", "largest");
		
		ConsumerConnector consumer = 
				Consumer.createJavaConsumerConnector(new ConsumerConfig(properties));
		
		Map<String, Integer> topicCountMap = new HashMap<String, Integer>();

		topicCountMap.put(TOPIC, NUM_THREADS);
		
		Map<String, List<KafkaStream<byte[], byte[]>>> consumerMap = 	consumer.createMessageStreams(topicCountMap);
		
		List<KafkaStream<byte[], byte[]>> streams = consumerMap.get(TOPIC);
		
		ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
		
		final SpecificDatumReader<COL_ONEM2M> specificDatumReader = new SpecificDatumReader<COL_ONEM2M>(COL_ONEM2M.class);
		
//		final AtomicInteger idx = new AtomicInteger(); 
		
		for (final KafkaStream<byte[], byte[]> stream : streams) {
			executor.execute(new Runnable() {
				@Override
				public void run() {
					for(MessageAndMetadata<byte[], byte[]> messageAndMetadata : stream) {
						
						StringBuffer sb = new StringBuffer();
						
						byte[] message = (byte[]) messageAndMetadata.message();
						
						BinaryDecoder binaryDecoder = DecoderFactory.get().binaryDecoder(message, null);
						COL_ONEM2M read = null;
						try {
							 read = specificDatumReader.read(null, binaryDecoder);
							 
							 List<java.lang.CharSequence> data= read.getData();
							 log.debug("count data from kafka broker : "+data.size());
							 
							 String task_group_id = read.getTaskGroupId().toString();
							 String task_id = read.getTaskId().toString();
							 String start_time = read.getStartTime().toString();
							 String colFrom = read.getColFrom().toString();
								
							 String eachTriple;
							 
							 // 작업 진행여부 판단
							 boolean processing_ok = false;
							 if(colFrom.equals(Utils.COL_SI_STATUS_DATA)) {
								 processing_ok = true;
							 } 

							 if(processing_ok) {
								 for(int i = 0; i < data.size(); i++) {
									//log.debug("data.get("+i+") : "+data.get(i));
									eachTriple = tripleService.getTriple(data.get(i).toString());
									//log.debug("eachTriple========>"+eachTriple);
									sb.append(eachTriple);
								 }
							 }
							 
							 /*
								//test start
								int id = idx.getAndIncrement();
								log.info("direct calling from mybatis without spring test start(id : "+id+")");
								String task_group_id = "PARKKKKKK_task_group_id_+"+id;
								String task_id = "PARKKKKK_task_id_"+id;
								int work_cnt = 10;
								String start_time = Utils.dateFormat.format(new Date());
								String end_time = Utils.dateFormat.format(new Date());
								schComm.insertSchHist(task_group_id, task_id, work_cnt, start_time, end_time);
								log.info("direct calling from mybatis without spring test end(id: "+id+")");
								//test end
							 */
							 
							 // task_group_id, task_id, start_time을 key로 work_result값을 update해줌
							 String finish_time = Utils.dateFormat.format(new Date());
							 String work_result  ="";
							 String triple_check_result_file = "";
							 String triple_path_file = "";
							 String triple_check_result = "";
							 String riot_mode = "";
							 
							 // triple data 체크및 전송정보
							 Map<String, String> hm = null;
							 if(processing_ok && data.size() > 0) {
								 hm = sendTriples(sb);
								 triple_check_result_file = hm.get("triple_check_result_file").equals("") ? Utils.None : hm.get("triple_check_result_file");
								 triple_path_file = hm.get("triple_path_file").equals("") ? Utils.None : hm.get("triple_path_file");
								 triple_check_result = hm.get("triple_check_result");
								 riot_mode = hm.get("riot_mode");
								 
								 if(riot_mode.equals("--skip")) {
									 triple_check_result = Utils.None;
								 } else {
									 if(triple_check_result.length() == 0) {
										 triple_check_result = Utils.Valid;
									 }
								 }
							 } else {
								 triple_path_file = Utils.None;
								 triple_check_result = Utils.None;
								 log.debug("data.size() is 0, does not send triple ......");
							 }

							 work_result =  "message " + data.size() + " counts from kafka broker have done ! ";
							 
							 SchComm schComm = new SchComm();
							 schComm.updateFinishTime(task_group_id, task_id, start_time, finish_time, work_result, triple_path_file, triple_check_result);							 
						} catch (Exception e) {
							e.printStackTrace();
						}
					}
				}
			});
		}
		
		//Thread.sleep(60000);

		//if (consumer != null) consumer.shutdown();
		//if (executor != null) executor.shutdown();
	}

	private Map<String, String> sendTriples(StringBuffer sb) throws Exception {
		String riot_mode = Utils.getSdaProperty("com.pineone.icbms.sda.riot.mode");
		String endDate = "";
		String triple_path_file = "";
		String triple_check_result = "";
		String start_time = Utils.dateFormat.format(new Date());
		String save_path  = Utils.getSdaProperty("com.pineone.icbms.sda.triple.save_path");
		
		// 폴더가 없으면 생성
		save_path = Utils.makeSavePath(save_path);

		// 스트링 버퍼에 있는 값을 파일에 기록한다.
		//String file_name = jec.getJobDetail().getName()+"_WRK" + endDate + "_TT" + start_time;
		String file_name = group_id+"_WRK" + endDate + "_TT" + start_time;
		String triple_check_result_file = "";
		
		// 결과값이 있을때만 triple파일을 만듬
		if( sb.length() > 0) {
			triple_path_file = save_path + "/"+ file_name + ".nt";			
			tripleService.makeTripleFile(triple_path_file, sb);
			
			// triple파일 체크
			if( ! riot_mode.equals("--skip")) {
				String[] check_result = tripleService.checkTripleFile(triple_path_file, file_name);
				
				// 점검결과를 파일로 저장한다.(체크결과 오류가 있는 경우만 파일로 만듬)
				if(! check_result[1].trim().equals("")) {
					triple_check_result_file = file_name+".bad";
					tripleService.makeResultFile(triple_check_result_file, check_result);
					
					// triple파일 체크결과값
					if(check_result[1].length() > 0) {
						triple_check_result = check_result[1]+ Utils.NEW_LINE+" with file '"+triple_check_result_file+"'";
					} else {
						triple_check_result = Utils.Valid;
					}
				}
			}
			// 파일 전송
			log.info("Sending triples in "+user_id+" start.......................");
			tripleService.sendTripleFile(triple_path_file);
			log.info("Sending triples in "+user_id+" end.......................");
		}

		Map<String, String> hm = new HashMap<String, String>();
		hm.put("triple_check_result_file", triple_check_result_file);
		hm.put("triple_path_file", triple_path_file);
		hm.put("triple_check_result", triple_check_result);
		hm.put("riot_mode", riot_mode);
		
		return hm;
	}
}
