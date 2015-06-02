package org.powertac.customer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.Interval;
import org.powertac.common.Contract;
import org.powertac.common.CustomerInfo;
import org.powertac.common.IdGenerator;
import org.powertac.common.RandomSeed;
import org.powertac.common.Contract.State;
import org.powertac.common.enumerations.PowerType;
import org.powertac.common.interfaces.BrokerProxy;
import org.powertac.common.interfaces.CustomerServiceAccessor;
import org.powertac.common.msg.ContractAccept;
import org.powertac.common.msg.ContractAnnounce;
import org.powertac.common.msg.ContractConfirm;
import org.powertac.common.msg.ContractDecommit;
import org.powertac.common.msg.ContractEnd;
import org.powertac.common.msg.ContractNegotiationMessage;
import org.powertac.common.msg.ContractOffer;
import org.powertac.common.timeseries.DayComparisonLoadForecast;
import org.powertac.common.timeseries.LoadForecast;
import org.powertac.common.timeseries.LoadTimeSeries;
import org.powertac.common.timeseries.TimeSeriesGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


public abstract class AbstractContractCustomer {
	static protected Logger log = Logger
			.getLogger(AbstractContractCustomer.class.getName());

	protected String name = "dummy";
	protected HashMap<PowerType, List<CustomerInfo>> customerInfos;
	protected List<CustomerInfo> allCustomerInfos;
	protected List<Contract> activeContracts;

	// Service accessor
	protected CustomerServiceAccessor service;
	
	

	/** The id of the Abstract Customer */
	protected long custId;

	/** Random Number Generator */
	protected RandomSeed rs1;

	protected LoadTimeSeries historicLoad;
	protected TimeSeriesGenerator generator;
	protected LoadForecast forecast;

	/**
	 * constructor, requires explicit setting of name
	 */
	public AbstractContractCustomer(DateTime now) {
		super();
		custId = IdGenerator.createId();
		customerInfos = new HashMap<PowerType, List<CustomerInfo>>();
		allCustomerInfos = new ArrayList<CustomerInfo>();
		generator =new TimeSeriesGenerator();
		forecast = new DayComparisonLoadForecast();
		activeContracts = new ArrayList<Contract>();
		this.historicLoad = generator.generateLoadTimeSeries(now.minusYears(1),
				now, (int) (custId % 3));		
	}

	
	public AbstractContractCustomer() {
		super();
		custId = IdGenerator.createId();
		customerInfos = new HashMap<PowerType, List<CustomerInfo>>();
		allCustomerInfos = new ArrayList<CustomerInfo>();
		generator =new TimeSeriesGenerator();
		forecast = new DayComparisonLoadForecast();
		activeContracts = new ArrayList<Contract>();
	}

	public void handleMessage(ContractNegotiationMessage message) {
		// TODO auf antworten von customers reagieren
	}

	// counter offer
	public void handleMessage(ContractOffer message) {		
		double utility = computeUtility(message);
		log.info("Offer arrived at Customer."+message+" Utility ="+utility);
		ContractAccept ca=new ContractAccept(message.getBroker(), message);
		service.getBrokerProxyService().sendMessage(message.getBroker(), ca);
	}

	// CONFIRM
	public void handleMessage(ContractConfirm message) {
		// TODO exception: should not be here
	}

	// END
	public void handleMessage(ContractEnd message) {
		log.info("Contract END arrived at Customer.");
		
	}

	public void handleMessage(ContractAccept message) {
		log.info("Contract ACCEPT arrived at Customer. Sending Confirm.");			
		
	}

	// DECOMMIT
	public void handleMessage(ContractDecommit message) {
		log.info("Contract DECOMMIT arrived at Customer.");
		

	}


	public double computeUtility(ContractOffer offer) {
		double utility = 0;	

		DateTime starttime = service.getTimeslotRepo().currentTimeslot().getStartTime();
		LoadTimeSeries loadForecastTS = forecast.calculateLoadForecast(
				historicLoad, starttime, starttime.plus(offer.getDuration()));
		utility += loadForecastTS.getTotalLoad() * offer.getEnergyPrice(); // total
																			// expected
																			// energy
																			// cost

		for (int month = 1; month <= 12; month++) {
			utility += loadForecastTS.getMaxLoad(month)
					* offer.getPeakLoadPrice(); // total expected peak load fee
		}


		if (activeContract(starttime)) {
			utility += offer.getEarlyWithdrawPayment();
		}

		// TODO utility for negotiation rounds, early agreement is better/worse

		return utility;
	}

	private boolean activeContract(DateTime startDate) {
		for (Contract c : activeContracts) {
			Interval interval = new Interval(c.getStartDate(), c.getEndDate());
			if( interval.contains(new DateTime(startDate))){
				return true;
			}
		}
		return false;
	}

	/**
	 * Provides a reference to the service accessor, through which we can get at
	 * sim services
	 */
	public void setServiceAccessor(CustomerServiceAccessor csa) {
		this.service = csa;
	}

	/**
	 * Initializes the instance. Called after configuration, and after a call to
	 * setServices(). TODO -- do we really want this here?
	 */
	public void initialize() {
		rs1 = service.getRandomSeedRepo().getRandomSeed(name, 0,
				"ContractCustomer");
		DateTime now=service.getTimeslotRepo().currentTimeslot().getStartInstant().toDateTime();
		this.historicLoad = generator.generateLoadTimeSeries(now.minusYears(1),
				now, (int) (custId % 3));
		for (Class<?> messageType : Arrays.asList(ContractOffer.class,
				ContractAccept.class, ContractAnnounce.class,
				ContractConfirm.class, ContractDecommit.class,
				ContractEnd.class)) {
			service.getBrokerProxyService().registerBrokerMessageListener(this, messageType);
		}
	}

	/**
	 * Saves model data to the bootstrap record. Default implementation does
	 * nothing; models may override if they aggregate objects that must save
	 * state.
	 */
	public void saveBootstrapState() {
	}

	/**
	 * Adds an additional CustomerInfo to the list
	 */
	public void addCustomerInfo(CustomerInfo info) {
		if (null == customerInfos.get(info.getPowerType())) {
			customerInfos.put(info.getPowerType(),
					new ArrayList<CustomerInfo>());
		}
		customerInfos.get(info.getPowerType()).add(info);
		allCustomerInfos.add(info);
	}

	/**
	 * Returns the first CustomerInfo associated with this instance and
	 * PowerType. It is up to individual models to fill out the fields.
	 */
	public CustomerInfo getCustomerInfo(PowerType pt) {
		return getCustomerInfoList(pt).get(0);
	}

	/**
	 * Returns the list of CustomerInfos associated with this instance and
	 * PowerType.
	 */
	public List<CustomerInfo> getCustomerInfoList(PowerType pt) {
		return customerInfos.get(pt);
	}

	/**
	 * Returns the list of CustomerInfo records associated with this customer
	 * model.
	 */
	public List<CustomerInfo> getCustomerInfos() {
		return new ArrayList<CustomerInfo>(allCustomerInfos);
	}

	@Override
	public String toString() {
		return Long.toString(getId()) + " " + getName();
	}

	public int getPopulation(CustomerInfo customer) {
		return customer.getPopulation();
	}

	public long getCustId() {
		return custId;
	}

	/** Synonym for getCustId() */
	public long getId() {
		return custId;
	}

	/** Sets the name for this model **/
	public void setName(String name) {
		this.name = name;
	}

	/** Returns the name of this model **/
	public String getName() {
		return name;
	}

	/**
	 * Called to run the model forward one step.
	 */
	public abstract void step();

}
