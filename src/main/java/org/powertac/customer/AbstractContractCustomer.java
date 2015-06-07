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
import org.powertac.common.enumerations.PowerType;
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

public abstract class AbstractContractCustomer {
	static protected Logger log = Logger
			.getLogger(AbstractContractCustomer.class.getName());

	protected String name = "dummy";
	protected HashMap<PowerType, List<CustomerInfo>> customerInfos;
	protected List<CustomerInfo> allCustomerInfos;
	protected HashMap<Long, Contract> activeContracts;

	// Service accessor
	protected CustomerServiceAccessor service;

	/** The id of the Abstract Customer */
	protected long custId;

	/** Random Number Generator */
	protected RandomSeed rs1;

	protected LoadTimeSeries historicLoad;
	protected TimeSeriesGenerator generator;
	protected LoadForecast forecast;

	/** max number of rounds for negotiation */
	protected static final int DEADLINE = 10;
	protected static final double discountingFactor = 0.1;
	protected HashMap<Long, Integer> negotiationRounds = new HashMap<Long, Integer>();

	protected double reservationEnergyPrice = 0.004;
	protected double reservationPeakLoadPrice = 70;
	protected double reservationEarlyExitPrice = 5000;
	protected long durationPreference = 1000 * 60 * 60 * 24 * 30;
	protected long maxDurationDeviation = 1000 * 60 * 60 * 24 * 7;

	/**
	 * constructor, requires explicit setting of name
	 */
	public AbstractContractCustomer(DateTime now) {
		super();
		custId = IdGenerator.createId();
		customerInfos = new HashMap<PowerType, List<CustomerInfo>>();
		allCustomerInfos = new ArrayList<CustomerInfo>();
		generator = new TimeSeriesGenerator();
		forecast = new DayComparisonLoadForecast();
		activeContracts = new HashMap<Long, Contract>();
		this.historicLoad = generator.generateLoadTimeSeries(now.minusYears(1),
				now, (int) (custId % 3));
	}

	public AbstractContractCustomer() {
		super();
		custId = IdGenerator.createId();
		customerInfos = new HashMap<PowerType, List<CustomerInfo>>();
		allCustomerInfos = new ArrayList<CustomerInfo>();
		generator = new TimeSeriesGenerator();
		forecast = new DayComparisonLoadForecast();
		activeContracts = new HashMap<Long, Contract>();
	}

	public void handleMessage(ContractNegotiationMessage message) {
		// TODO auf antworten von customers reagieren
	}

	// counter offer
	public void handleMessage(ContractOffer message) {
		updateNegotiationRound(message.getBroker().getId());
		double utility = computeUtility(message, message.getDuration());
		log.info("Offer arrived at Customer." + message + " Utility ="
				+ utility + " Round ="
				+ negotiationRounds.get(message.getBroker().getId()));

		if (negotiationRounds.get(message.getBroker().getId()) > DEADLINE) {
			ContractEnd ce = new ContractEnd(message.getBroker(), message);
			service.getBrokerProxyService().sendMessage(
					message.getBroker(), ce);
		} else {

			// buyer role
			if (message.getPowerType() == PowerType.CONSUMPTION) {
				ContractOffer co = generateCounterOffer(message);

				double counterOfferUtility = computeUtility(co,
						co.getDuration());

				// cant find a better option --> ACCEPT
				if (utility > counterOfferUtility) {
					ContractAccept ca = new ContractAccept(message);
					service.getBrokerProxyService().sendMessage(
							message.getBroker(), ca);
				} else {
					// can find a better option --> COUNTEROFFER
					service.getBrokerProxyService().sendMessage(
							message.getBroker(), co);
				}
			}
			// seller role
			else if (message.getPowerType() == PowerType.PRODUCTION) {
				ContractOffer co = generateCounterOffer(message);

			}

		}

	}

	private ContractOffer generateCounterOffer(ContractOffer message) {
		return new ContractOffer(message.getBroker(), this.custId,
				reservationEnergyPrice, reservationPeakLoadPrice,
				durationPreference, reservationEarlyExitPrice,
				message.getPowerType());

	}

	private void updateNegotiationRound(long id) {
		if (negotiationRounds.containsKey(id)
				&& negotiationRounds.get(id) <= DEADLINE) {
			negotiationRounds.put(id, negotiationRounds.get(id) + 1);
		} else {
			negotiationRounds.put(id, 1);
		}

	}

	// CONFIRM
	public void handleMessage(ContractConfirm message) {
		activeContracts.put(message.getContractId(), service.getContractRepo()
				.findContractById(message.getContractId()));
		negotiationRounds.put(message.getBroker().getId(), 0);
	}

	// END
	public void handleMessage(ContractEnd message) {
		log.info("Contract END arrived at Customer.");
		negotiationRounds.put(message.getBroker().getId(), 0);

	}

	public void handleMessage(ContractAccept message) {
		log.info("Contract ACCEPT arrived at Customer. Sending Confirm.");
		ContractConfirm cf = new ContractConfirm(message.getBroker(), message);
		service.getBrokerProxyService().sendMessage(message.getBroker(), cf);
		negotiationRounds.put(message.getBroker().getId(), 0);
	}

	/** DECOMMIT is only allowed if this is a producer customer */
	public void handleMessage(ContractDecommit message) {
		log.info("Contract DECOMMIT arrived at Customer.");

		if (this.getCustomerInfo(PowerType.PRODUCTION) != null) {
			activeContracts.remove(message.getContractId());
			ContractConfirm cf = new ContractConfirm(message.getBroker(),
					message);
			service.getBrokerProxyService()
					.sendMessage(message.getBroker(), cf);
			negotiationRounds.put(message.getBroker().getId(), 0);
		} else {
			log.error("Trying to DECOMMIT from a consumer customer. This is not possible");
		}
	}

	public double computeUtility(ContractOffer offer, long duration) {
		if (offer.getPowerType() == PowerType.CONSUMPTION) {
			return computeEnergyPriceUtilityBuyer(offer, duration)
					+ computePeakLoadPriceUtilityBuyer(offer, duration)
					+ computeEarlyWithdrawUtility(offer);
		} else if (offer.getPowerType() == PowerType.PRODUCTION) {
			return computeEnergyPriceUtilitySeller(offer, duration)
					+ computePeakLoadPriceUtilitySeller(offer, duration)
					+ computeEarlyWithdrawUtility(offer);
		}

		return -1;
	}

	public double computeEnergyPriceUtilityBuyer(ContractOffer offer,
			long duration) {
		double utility = 0;

		DateTime starttime = service.getTimeslotRepo().currentTimeslot()
				.getStartTime();
		LoadTimeSeries loadForecastTS = forecast.calculateLoadForecast(
				historicLoad, starttime, starttime.plus(duration));
		utility += loadForecastTS.getTotalLoad()
				* (reservationEnergyPrice - offer.getEnergyPrice()); // total
		// expected
		// energy
		// cost
		// TIME DISCOUNTING
		utility = utility
				* Math.pow(discountingFactor,
						negotiationRounds.get(offer.getBroker().getId()));

		return utility;
	}

	public double computeEnergyPriceUtilitySeller(ContractOffer offer,
			long duration) {
		double utility = 0;

		DateTime starttime = service.getTimeslotRepo().currentTimeslot()
				.getStartTime();
		LoadTimeSeries loadForecastTS = forecast.calculateLoadForecast(
				historicLoad, starttime, starttime.plus(duration));
		utility += loadForecastTS.getTotalLoad()
				* (offer.getEnergyPrice() - reservationEnergyPrice); // total
		// expected
		// energy
		// cost
		// TIME DISCOUNTING
		utility = utility
				* Math.pow(discountingFactor,
						negotiationRounds.get(offer.getBroker().getId()));

		return utility;
	}

	public double computePeakLoadPriceUtilityBuyer(ContractOffer offer,
			long duration) {
		double utility = 0;
		DateTime starttime = service.getTimeslotRepo().currentTimeslot()
				.getStartTime();
		LoadTimeSeries loadForecastTS = forecast.calculateLoadForecast(
				historicLoad, starttime, starttime.plus(duration));

		for (int month = 1; month <= 12; month++) {
			utility += loadForecastTS.getMaxLoad(month)
					* (reservationPeakLoadPrice - offer.getPeakLoadPrice()); // total
																				// expected
																				// peak
																				// load
																				// fee
		}
		// TIME DISCOUNTING
		utility = utility
				* Math.pow(discountingFactor,
						negotiationRounds.get(offer.getBroker().getId()));

		return utility;
	}

	public double computePeakLoadPriceUtilitySeller(ContractOffer offer,
			long duration) {
		double utility = 0;
		DateTime starttime = service.getTimeslotRepo().currentTimeslot()
				.getStartTime();
		LoadTimeSeries loadForecastTS = forecast.calculateLoadForecast(
				historicLoad, starttime, starttime.plus(duration));

		for (int month = 1; month <= 12; month++) {
			utility += loadForecastTS.getMaxLoad(month)
					* (offer.getPeakLoadPrice() - reservationPeakLoadPrice); // total
																				// expected
																				// peak
																				// load
																				// fee
																				// -
																				// fee
																				// with
																				// reservation
																				// price
		}
		// TIME DISCOUNTING
		utility = utility
				* Math.pow(discountingFactor,
						negotiationRounds.get(offer.getBroker().getId()));

		return utility;
	}

	public double computeEarlyWithdrawUtility(ContractOffer offer) {
		double utility = 0;
		DateTime starttime = service.getTimeslotRepo().currentTimeslot()
				.getStartTime();

		if (activeContract(starttime)) {
			utility += offer.getEarlyWithdrawPayment();
		}

		// TIME DISCOUNTING
		utility = utility
				* Math.pow(discountingFactor,
						negotiationRounds.get(offer.getBroker().getId()));
		return utility;
	}

	private boolean activeContract(DateTime startDate) {
		for (Contract c : activeContracts.values()) {
			Interval interval = new Interval(c.getStartDate(), c.getEndDate());
			if (interval.contains(new DateTime(startDate))) {
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
	 * setServices(). 
	 */
	public void initialize() {
		rs1 = service.getRandomSeedRepo().getRandomSeed(name, 0,
				"ContractCustomer");
		DateTime now = service.getTimeslotRepo().currentTimeslot()
				.getStartInstant().toDateTime();
		this.historicLoad = generator.generateLoadTimeSeries(now.minusYears(1),
				now, (int) (custId % 3));
		for (Class<?> messageType : Arrays.asList(ContractOffer.class,
				ContractAccept.class, ContractAnnounce.class,
				ContractConfirm.class, ContractDecommit.class,
				ContractEnd.class)) {
			service.getBrokerProxyService().registerBrokerMessageListener(this,
					messageType);
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
