package org.app.iiitb.peorig.multipledays;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Map.Entry;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.ResCloudlet;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.UtilizationModel;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.VmSchedulerSpaceShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.xml.sax.SAXException;


public class Simulator {

	String tracefile;
	String datacenterxml="datacenters.xml";
	double threshold;
	String loadforwardfile;
	String logfile;
	String energy_prefix;
	String load_prefix;
	String jobloadstr;
	PrintWriter resultwriter;
	PrintWriter renpWriter;
	PrintWriter energyWriter;
	
	String act_energy_prefix;
//	= "./ActualEnergy/energy_act_file";
	
    private Map<Integer,String> dcIdMap;
	private List<ResCloudlet> ls;

	double pcps= 0.03/1000;		//Power consumption by one host at idle (((655-115)/5)/3600) 
	private static final double idleEnergyPerHost = 0.115;  	//in KWs
//	double pcps=(10*0.0125)/1000;	//0.0125/1000;//Power consumption by one host at idle ((525-300)/5)/3600) OR (((475-115)/5)/3600)
//	private static final double idleEnergyPerHost = 0.3;	//in KWs
	private static final int numHosts = 100;
	private static final int numVms = 5;	//numPes=numVms
	private static final double energyThreshold = 30; 	//10
	
	private static final String vmStartSuffix = "0001";
    private static final int DAY_HRS = 24; 
    
	private static final String COMMENT = ";"; 
    private static final int JOB_NUM = 1 - 1; 
    private static final int SUBMIT_TIME = 2 - 1; 
    private static final int RUN_TIME = 4 - 1;
    private static final int MAX_FIELD = 18; 
    
    private long endst=-1;
    private long firstst=-1;
    
    
	public Simulator(String tracefile, String datacenterxml, double threshold, String loadforwardfile, String logfile,
			String energy_prefix, String load_prefix, String act_energy_prefix, String jobloadstr, 
			PrintWriter resultwriter, PrintWriter renpWriter, PrintWriter energyWriter) {
		super();
		this.tracefile = tracefile;
		this.datacenterxml = datacenterxml;
		this.threshold = threshold;
		this.loadforwardfile = loadforwardfile;
		this.logfile = logfile;
		this.energy_prefix = energy_prefix;
		this.load_prefix = load_prefix;
		this.act_energy_prefix = act_energy_prefix;
		this.jobloadstr = jobloadstr;
		this.resultwriter = resultwriter;
		this.renpWriter = renpWriter;
		this.energyWriter = energyWriter;
	}

	void runSimulation() throws IOException, InterruptedException, ParserConfigurationException, SAXException, TransformerException {

        Log.setOutput(new FileOutputStream(logfile));
        Log.disable();
        //need to setup XML file before running this
		XMLReaderDom xrd=new XMLReaderDom();
		List<DataCenterDetails> dsd=xrd.getDataCenterDetailsXML(datacenterxml);

		int num_user = 3000;
        Calendar calendar = Calendar.getInstance();
        boolean trace_flag = false;
        CloudSim.init(num_user, calendar, trace_flag);
  
        //Creating 4 Datacenters - each one has 100 host with 5 PEs, 500 VMs
        Datacenter dc[]=new Datacenter[dsd.size()];
        ResDatacenterBroker broker[]=new ResDatacenterBroker[dsd.size()];

        Map<DataCenterDetails, ResDatacenterBroker> dsdBrokerList = new HashMap<DataCenterDetails,ResDatacenterBroker>();
        dcIdMap = new HashMap<Integer,String>();
        
        int ite=0;
        for(DataCenterDetails d:dsd) {
        	dc[ite] = createDatacenter(d.getId(), numHosts,numVms);
        	Log.printLine(dc[ite].getId()+" "+dc[ite].getName());
        	dcIdMap.put(dc[ite].getId(), dc[ite].getName());
        	
        	broker[ite] = createBroker(d.getBroker());

        	dsdBrokerList.put(d, broker[ite]);
        	String x = Integer.toString(ite+1)+vmStartSuffix;
        	broker[ite].submitVmList(createVM(broker[ite].getId(), numHosts*numVms, Integer.parseInt(x)));
        	ite++;
        }
//        System.out.println("Datacenters created: "+dsdBrokerList.size());
//        System.out.println(dcIdMap);
        dsdBrokerList = sortDsdBrokerList(dsdBrokerList);

        ls=createCloudletWithoutBroker(dsdBrokerList);
        if(endst!=-100)
        	scheduleCloudlets(ls,dsdBrokerList);
        
        //Starting simulation here
        CloudSim.startSimulation();

        List<Cloudlet> newList = new LinkedList<Cloudlet>();
        for(ResDatacenterBroker dcb:broker) {
        	newList.addAll(dcb.getCloudletReceivedList());
        }
        CloudSim.stopSimulation();
        
        Log.enable();
        Log.setOutput(new FileOutputStream(loadforwardfile, true));
        printCloudletList(newList);
        
        showFinalResult(broker);
	}
	
	public void scheduleCloudlets(List<ResCloudlet> cloudletList, 
								Map<DataCenterDetails, ResDatacenterBroker> dsdBrokerList) {
		
		HashMap<DataCenterDetails, List<ResCloudlet>> clmap = new HashMap<DataCenterDetails,List<ResCloudlet>>();

		List<DataCenterDetails> lowEnergyDC = new ArrayList<DataCenterDetails>();
		for(DataCenterDetails x:dsdBrokerList.keySet()) {
//			if(x.getId().equals("DC4"))
//				System.out.println(x.getGreenEnergy()+" "+x.getId());
			if(x.getGreenEnergy()<energyThreshold) {
				lowEnergyDC.add(x);
			}
			clmap.put(x, new LinkedList<ResCloudlet>());
		}
    	for(ResCloudlet cloudlet : cloudletList) {
//    		System.out.println(cloudlet.getCloudletId());
    		boolean bool=true;
    		long tym = (cloudlet.getStartTime()-1)%60;
    		while(bool==true) {
				tym=(tym+1)%60;
	    		for(DataCenterDetails d:dsdBrokerList.keySet()) {
	    			if(d.getGreenEnergy()<10 && lowEnergyDC.size()>1) {
//	    				System.out.println("+++++++++++++++++++++++++-----------+++++++++++++++++++"+lowEnergyDC.size());
//	    				if(d.getGreenEnergy()>0)
//	    					System.out.println(d.getGreenEnergy());
	    				List<DataCenterDetails> keysAsArray = new ArrayList<DataCenterDetails>(lowEnergyDC);
	    				Random r = new Random();
	    				DataCenterDetails randkey = keysAsArray.get(r.nextInt(keysAsArray.size()));
	    				cloudlet.getCloudlet().setUserId(dsdBrokerList.get(randkey).getId());
	    				clmap.get(randkey).add(cloudlet);
	    				bool=false; break;
	    			}
	    			if(d.getCapacity()[(int) tym] < threshold) {
	    				cloudlet.getCloudlet().setUserId(dsdBrokerList.get(d).getId());
	    				clmap.get(d).add(cloudlet);
	    				bool=false; break;
	    			}
	    		}
    		}
    	}
    	for(DataCenterDetails x:dsdBrokerList.keySet()) {
    		if(clmap.get(x).isEmpty())
    			continue;
    		dsdBrokerList.get(x).submitResCloudletList(clmap.get(x));
		}   	
    }

    private List<Vm> createVM(int userId, int vms, int idShift) {
        //Creates a container to store VMs. This list is passed to the broker later
        LinkedList<Vm> list = new LinkedList<Vm>();

        //VM Parameters
        long size = 10000; //image size (MB)
        int ram = 512; //vm memory (MB)
        int mips = 1000;
        long bw = 1000;
        int pesNumber = 1; //number of cpus
        String vmm = "Xen"; //VMM name

        //create VMs
        Vm[] vm = new Vm[vms];
        
        for (int i = 0; i < vms; i++) {
            vm[i] = new Vm(idShift + i, userId, mips, pesNumber, ram, bw, size, vmm, new CloudletSchedulerSpaceShared());
            list.add(vm[i]);
        }
        
        return list;
    }
    
    private Map<DataCenterDetails, ResDatacenterBroker> 
    	updateDSD(Map<DataCenterDetails, ResDatacenterBroker> dsdBrokerList) {
    	XMLReaderDom xrd=new XMLReaderDom();
    	List<DataCenterDetails> dsd=xrd.getDataCenterDetailsXML(datacenterxml);
    	for(DataCenterDetails d:dsdBrokerList.keySet()) {
    		for(DataCenterDetails justread: dsd) {
    			if(d.getId().equals(justread.getId())) {
    				d.setCapacity(justread.getCapacity());
    				d.setGreenEnergy(justread.getGreenEnergy());
    			}
    		}
		}
    	return dsdBrokerList;
    }
    
//    private static List<ResCloudlet> createCloudletWithoutBroker(int cloudlets, int idShift) throws IOException {
    private List<ResCloudlet> createCloudletWithoutBroker(Map<DataCenterDetails, ResDatacenterBroker> dsdBrokerList) throws IOException, ParserConfigurationException, SAXException, TransformerException {
    	LinkedList<ResCloudlet> list = new LinkedList<ResCloudlet>();
    	BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(tracefile)));
        
        int lineNum = 1;
        String line = null;
//        Cloudlet cloudlet;
        ResCloudlet rcl;
        while (reader.ready() && (line = reader.readLine()) != null) {
            rcl=parseValue(line, lineNum);
            if(rcl!=null) {
            	if(endst==-1) {
            		firstst = rcl.getStartTime();
            		endst=firstst;
//            		System.out.println("firstst: "+firstst);
            		for(int i=1;i<=dsdBrokerList.size();i++) {
//            			System.out.println("Updating xml: "+(i)+" "+thishr+" "+dsdBrokerList.size());
            			//line thishr needs to be updated in xml
            			String energyfilename=energy_prefix + i;
            			String loadfilename=load_prefix + i;
            			XMLReaderDom xrd=new XMLReaderDom();
            			xrd.updateXML(i,1,energyfilename,loadfilename,datacenterxml);
            		}
                }
            	if(rcl.getStartTime() >= (endst+3600) || endst==-1) {
            		if(endst!=-1) {
            			scheduleCloudlets(list,dsdBrokerList);
            		}
//            		endst=rcl.getStartTime();
            		endst+=3600;
            		long thishr = (endst-firstst)/3600 + 1;
            		if(thishr > DAY_HRS) {
            			System.out.println("endst: "+endst+" "+thishr);
            			endst=-100;
            			break;
            		}
            		//scheduling this hour's cloudlets
            		//insert python codes and XML read codes here
            		for(int i=1;i<=dsdBrokerList.size();i++) {
//            			System.out.println("Updating xml: "+(i)+" "+thishr+" "+dsdBrokerList.size());
            			//line thishr needs to be updated in xml
            			String energyfilename=energy_prefix + i;
            			String loadfilename=load_prefix + i;
            			XMLReaderDom xrd=new XMLReaderDom();
            			xrd.updateXML(i,thishr,energyfilename,loadfilename,datacenterxml);
            		}
            		dsdBrokerList = updateDSD(dsdBrokerList);	//read XML file and update DSD 
            		dsdBrokerList = sortDsdBrokerList(dsdBrokerList);
//            		System.out.println(dsdBrokerList);
            		
            		list = new LinkedList<ResCloudlet>();
            		list.add(rcl);
            		continue;
            	}

            	list.add(rcl);
            }
            lineNum++;
        }
        if(!reader.ready()) {
        	endst=-10;
        }
        reader.close();
        return list;
    }
    
    private ResCloudlet parseValue(final String line, final int lineNum) {
    	String[] fieldArray = new String[MAX_FIELD];
        // skip a comment line
        if (line.startsWith(COMMENT)) {
                return null;
        }

        final String[] sp = line.split("\\s+"); // split the fields based on a
        // space
        int len = 0; // length of a string
        int index = 0; // the index of an array

        // check for each field in the array
        for (final String elem : sp) {
                len = elem.length(); // get the length of a string

                // if it is empty then ignore
                if (len == 0) {
                        continue;
                }
                fieldArray[index] = elem;
                index++;
        }

//        if (index == MAX_FIELD) {
        if(index >= 4) {
                return extractField(fieldArray, lineNum);
        }
        return null;
}
    
    private ResCloudlet extractField(final String[] array, final int line) {
    	int pesNumber = 1;
        long fileSize = 0;
        long outputSize = 0;
    	
    	int id=new Integer(array[JOB_NUM].trim());
    	long submitTime = new Long(array[SUBMIT_TIME].trim());
//    	int reqRunTime=new Integer(array[REQ_RUN_TIME].trim());
    	int length=new Integer(array[RUN_TIME].trim());
//    	int userID = new Integer(array[USER_ID].trim()).intValue();
    	
//    	System.out.println(id+" "+submitTime+" "+length);
    	UtilizationModel utilizationModel = new UtilizationModelFull();
    	
    	Cloudlet cloudlet = new Cloudlet(id, length, pesNumber, fileSize, outputSize, utilizationModel, utilizationModel, utilizationModel);
    	ResCloudlet rcl = new ResCloudlet(cloudlet,submitTime,length,-1);
    	return rcl;
    }
    
    private Datacenter createDatacenter(String name, int hostNumber, int numpe) {
        
        
        List<Host> hostList = new ArrayList<Host>();
        List<Pe> peList1 = new ArrayList<Pe>();
        
        int mips = 1000;
        int hostId = 0;
        int ram = 16384;
        long storage = 1000000;
        int bw = 10000;
        
        for(int i=0;i<numpe;i++) {
        	peList1.add(new Pe(i, new PeProvisionerSimple(mips)));
        }
        
        for (int i = 0; i < hostNumber; i++) {
            hostList.add(
                    new Host(
                    hostId,
                    new RamProvisionerSimple(ram),
                    new BwProvisionerSimple(bw),
                    storage,
                    peList1,
                    new VmSchedulerSpaceShared(peList1)));
            
            hostId++;
        }
        
        
        String arch = "x86";      // system architecture
        String os = "Linux";          // operating system
        String vmm = "Xen";
        double time_zone = 10.0;         // time zone this resource located
        double cost = 3.0;              // the cost of using processing in this resource
        double costPerMem = 0.05;		// the cost of using memory in this resource
        double costPerStorage = 0.1;	// the cost of using storage in this resource
        double costPerBw = 0.1;			// the cost of using bw in this resource
        LinkedList<Storage> storageList = new LinkedList<Storage>();	//we are not adding SAN devices by now

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                arch, os, vmm, hostList, time_zone, cost, costPerMem, costPerStorage, costPerBw);
        
        Datacenter datacenter = null;
        try {
            datacenter = new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), storageList, 0);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        return datacenter;
    }
    
    private ResDatacenterBroker createBroker(String name) {
        
        ResDatacenterBroker broker = null;
        try {
            broker = new ResDatacenterBroker(name);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return broker;
    }

    /**
     * Prints the Cloudlet objects
     *
     * @param list list of Cloudlets
     * @throws FileNotFoundException 
     */
    private void printCloudletList(List<Cloudlet> list) throws FileNotFoundException {
        int size = list.size();
        Cloudlet cloudlet;
        String indent = "    ";
//        Log.printLine();
        Log.printLine("========== OUTPUT ==========");
        Log.printLine("Cloudlet ID" + indent + "STATUS" + indent
                + "Data center ID" + indent + "VM ID" + indent + indent + "Time" + indent 
                + "Submision Time" + indent + "Start Time" + indent + "Finish Time" + indent + "Wait Time");
        
        DecimalFormat dft = new DecimalFormat("###.##");
        for (int i = 0; i < size; i++) {
            cloudlet = list.get(i);
            Log.print(indent + cloudlet.getCloudletId() + indent + indent);
//          if (cloudlet.getCloudletStatus() == Cloudlet.SUCCESS) {
            if (cloudlet.getStatus() == Cloudlet.SUCCESS) {
                Log.print("SUCCESS");
                
                Log.printLine(indent + indent + dcIdMap.get(cloudlet.getResourceId()) + indent + indent + indent + cloudlet.getVmId()
                        + indent + indent + indent + dft.format(cloudlet.getActualCPUTime())
                        + indent + indent + dft.format(cloudlet.getSubmissionTime())
                        + indent + indent + dft.format(cloudlet.getExecStartTime()) + indent + indent + indent + dft.format(cloudlet.getFinishTime())
                        + indent + indent + dft.format(cloudlet.getWaitingTime())
                		);
            }
            //find energy usage
            //match data center with 1,2,3,4
            //then check start time, end time of the cloudlet
            //subtract ren energy by the amount of enrgy consumed by the job
            //if ren energy less than 0, then non ren energy is used
            //
        }
    }
    
    private void showFinalResult(ResDatacenterBroker broker[]) throws IOException {
    	DecimalFormat _numberFormat= new DecimalFormat("#0.000");
    	resultwriter.println("############################################################");
    	List<Cloudlet> newList;
    	int i=1;
    	int totjobs = 0;
    	String renpWriter_print="";
    	String energyWriter_print="";
        for(ResDatacenterBroker dcb:broker) {
        	int numjobsthishr[] = new int[24];
        	String jobloadfilename=jobloadstr+i+".csv";
        	PrintWriter writer = new PrintWriter(jobloadfilename, "UTF-8");
        	writer.println("Number of Jobs");
        	newList = dcb.getCloudletReceivedList();
        	totjobs+=newList.size();
        	resultwriter.print("DC"+i+" - Number of jobs: "+newList.size()+"; ");
			@SuppressWarnings("rawtypes")
			List energy_act = Files.readAllLines(Paths.get(act_energy_prefix+i));
			List<Double> renenergydata = new ArrayList<Double>();
			for(Object eobj:energy_act) {
				renenergydata.add(Double.parseDouble((String) eobj));
			}
			
			List<Double> workingenergydata = new ArrayList<Double>();
			
			for(int iter=0;iter<24;iter++) {
				double totalIdleEnergyPerHour = idleEnergyPerHost*numHosts;
				workingenergydata.add(totalIdleEnergyPerHour);
			}
			double waittime =0;
			for(Cloudlet cl : newList) {
				double runtime = cl.getActualCPUTime();
				double starttime = cl.getExecStartTime() - firstst;
				waittime += cl.getWaitingTime();
//				System.out.println(cl.getExecStartTime()+" "+starttime);
//				double endtime = cl.getFinishTime();
				
				double startsec = starttime%3600;
				int starthr = (int)starttime/3600;
				if(starttime>=86400)
					continue;
				numjobsthishr[starthr]++;
//				300W-idle 150W-avg load 0.0125
				if(runtime < 3600-startsec) {
					double pc = pcps*runtime;
					workingenergydata.set(starthr, workingenergydata.get(starthr)+pc);
				}
				else { 				//if(runtime > 3600-startsec) {
					double pc = pcps * (3600-startsec);
					int thishr = starthr;
					workingenergydata.set(thishr, workingenergydata.get(thishr)+pc);
					runtime = runtime-(3600-startsec);
					thishr++;
					while(runtime!=0 && thishr<24) {
						if(runtime < 3600) {
							pc = pcps*runtime;
							workingenergydata.set(thishr, workingenergydata.get(thishr)+pc);
							thishr++;
							runtime = 0;
						}
						else {			//runtime>=3600
							runtime=runtime-3600;
						}
					}
				}
			}
			
			waittime = waittime/newList.size();
			
			double sum=0;
			double rensum=0;
			for(int iter=0;iter<24;iter++) {
				double n = workingenergydata.get(iter);
				double renn = renenergydata.get(iter);
				sum+=n;
				if(renn > n) {
					rensum+=n;
				}
				else {
					rensum+=renn;
				}
			}
//			System.out.println(workingenergydata);
//			System.out.println(renenergydata);
			double renpercent = (rensum/sum)*100;
			renpWriter_print += _numberFormat.format(renpercent)+",";
			energyWriter_print += _numberFormat.format(sum)+",";
			energyWriter_print += _numberFormat.format(rensum)+",";
			
			resultwriter.println("Average wait time: "+ _numberFormat.format(waittime) +" secs");
			resultwriter.println("Total Energy consumption: "+_numberFormat.format(sum)+" KW");
			resultwriter.println("Renewable energy used: "+_numberFormat.format(rensum)+" KW");
			resultwriter.println("RenPercent: " + _numberFormat.format(renpercent) + "%");
			i++;
			for(int iter=0;iter<24;iter++) {
//				resultwriter.print(numjobsthishr[iter]+" ");
				writer.println(numjobsthishr[iter]);
			}
			resultwriter.println();
			
			writer.close();
        }
        renpWriter.println(renpWriter_print.substring(0, renpWriter_print.length()-1)); 
        energyWriter.println(energyWriter_print.substring(0, energyWriter_print.length()-1));
        resultwriter.println("Total Jobs: "+totjobs);
    	resultwriter.println("############################################################");
    }
    
    private Map<DataCenterDetails, ResDatacenterBroker> sortDsdBrokerList(Map<DataCenterDetails, ResDatacenterBroker> unsortMap){

    	List<Map.Entry<DataCenterDetails, ResDatacenterBroker>> list =
                new LinkedList<Map.Entry<DataCenterDetails, ResDatacenterBroker>>(unsortMap.entrySet());

    	Collections.sort(list,new Comparator<Map.Entry<DataCenterDetails, ResDatacenterBroker>>() {

			@Override
			public int compare(Entry<DataCenterDetails, ResDatacenterBroker> o1,
					Entry<DataCenterDetails, ResDatacenterBroker> o2) {
				return (o1.getKey()).compareTo(o2.getKey());
			}

		});
//    	System.out.println(list);
    	
    	Map<DataCenterDetails, ResDatacenterBroker> sortedmap = new LinkedHashMap<DataCenterDetails, ResDatacenterBroker>();
    	
    	for(Map.Entry<DataCenterDetails, ResDatacenterBroker> dd : list) {
    		sortedmap.put(dd.getKey(), dd.getValue());
    	}
//    	System.out.println(sortedmap);
    	return sortedmap;
    }

}

