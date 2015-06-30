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
import org.powertac.common.enumerations.ContractIssue;
import org.powertac.common.enumerations.PowerType;
import org.powertac.common.exceptions.PowerTacException;
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
	protected static final double timeDiscountingFactor = 1;
	/**
	 * 1 = linear, <1 boulware and conceder for >1
	 */
	protected double counterOfferFactor = 1;
	protected HashMap<Long, Integer> negotiationRounds = new HashMap<Long, Integer>();

	protected double reservationEnergyPrice = 0.002;
	protected double reservationPeakLoadPrice = 65;
	protected double reservationEarlyExitPrice = 5000;
	protected double initialEnergyPrice = 0.006;
	protected double initialPeakLoadPrice = 75;
	protected double initialEarlyExitPrice = 5000;
	protected long durationPreference = 1000 * 60 * 60 * 24 * 365L;
	protected long maxDurationDeviation = 1000 * 60 * 60 * 24 * 180L;

	/**
	 * constructor, requires explicit setting of name
	 */
	public AbstractContractCustomer(DateTime now) {
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
		// custId = IdGenerator.createId();
		customerInfos = new HashMap<PowerType, List<CustomerInfo>>();
		allCustomerInfos = new ArrayList<CustomerInfo>();
		generator = new TimeSeriesGenerator();
		forecast = new DayComparisonLoadForecast();
		activeContracts = new HashMap<Long, Contract>();
	}

	// counter offer
	public void handleMessage(ContractOffer message) {
		if (isValidMessage(message))
			processOffer(message, true);

	}

	private boolean isValidMessage(ContractNegotiationMessage message) {
		for (CustomerInfo ci : service.getCustomerRepo().findByName(getName())) {
			if (ci.getId() == message.getCustomerId())
				return true;
		}
		return false;
	}

	private void processOffer(ContractOffer message, boolean canAccept) {
		updateNegotiationRound(message.getBroker().getId());

		log.info("Offer arrived at Customer." + message + " Round ="
				+ getRound(message));

		if (getRound(message) > DEADLINE) {
			ContractEnd ce = new ContractEnd(message.getBroker(), message);
			service.getBrokerProxyService()
					.sendMessage(message.getBroker(), ce);
		} else {
			double utility = 0.;
			double counterOfferUtility = 0.;
			double coPeakLoadPrice = 0.;
			double coEnergyPrice = 0.;
			long coDuration = 0;
			double coEarlyWithdrawPrice = 0;
			// buyer role
			if (message.getPowerType() == PowerType.CONSUMPTION) {

				// Energy Price
				ContractOffer co = new ContractOffer(message);
				if (!message.isAcceptedEnergyPrice()
						&& message.isDiscussedIssue(ContractIssue.ENERGY_PRICE)) {
					coEnergyPrice = generateOfferPriceBuyer(initialEnergyPrice,
							reservationEnergyPrice, getRound(message));
					co.setEnergyPrice(coEnergyPrice);
					utility = computeEnergyPriceUtilityBuyer(message,
							message.getDuration());
					counterOfferUtility = computeEnergyPriceUtilityBuyer(co,
							co.getDuration());
					log.info("Energy Price Eval: " + message + "CounterOffer: "
							+ co + " Round =" + getRound(message) + " Utility="
							+ utility + "CO-Utility=" + counterOfferUtility);
					// cant find a better option --> ACCEPT
					if (canAccept && utility >= counterOfferUtility) {
						ContractAccept ca = new ContractAccept(message);
						ca.setAcceptedEnergyPrice(true);
						resetNegotiationRound(message.getBroker().getId());
						service.getBrokerProxyService().sendMessage(
								message.getBroker(), ca);
						return;
					}
				}

				// Peak Load Price
				if (!message.isAcceptedPeakLoadPrice()
						&& message
								.isDiscussedIssue(ContractIssue.PEAK_LOAD_PRICE)) {
					co = new ContractOffer(message);
					coPeakLoadPrice = generateOfferPriceBuyer(
							initialPeakLoadPrice, reservationPeakLoadPrice,
							getRound(message));
					co.setPeakLoadPrice(coPeakLoadPrice);
					utility = computePeakLoadPriceUtilityBuyer(message,
							message.getDuration());
					counterOfferUtility = computePeakLoadPriceUtilityBuyer(co,
							co.getDuration());
					log.info("Peak Load Eval: " + message + "CounterOffer: "
							+ co + " Round =" + getRound(message) + " Utility="
							+ utility + "CO-Utility=" + counterOfferUtility);
					// cant find a better option --> ACCEPT
					if (canAccept && utility >= counterOfferUtility) {
						ContractAccept ca = new ContractAccept(message);
						ca.setAcceptedPeakLoadPrice(true);
						resetNegotiationRound(message.getBroker().getId());
						service.getBrokerProxyService().sendMessage(
								message.getBroker(), ca);
						return;
					}
				}

				// Duration
				if (!message.isAcceptedDuration()
						&& message.isDiscussedIssue(ContractIssue.DURATION)) {
					co = new ContractOffer(message);
					coDuration = generateDuration(message.getDuration(), durationPreference, maxDurationDeviation, getRound(message));
					co.setDuration(coDuration);
					utility = computeUtility(message, message.getDuration());
					counterOfferUtility = computeUtility(co, co.getDuration());
					log.info("DUration Eval: " + message + "CounterOffer: "
							+ co + " Round =" + getRound(message) + " Utility="
							+ utility + "CO-Utility=" + counterOfferUtility);
					// cant find a better option --> ACCEPT
					if (canAccept && message.getDuration()<= durationPreference + maxDurationDeviation && message.getDuration()>= durationPreference-maxDurationDeviation &&  utility >= counterOfferUtility) {
						ContractAccept ca = new ContractAccept(message);
						ca.setAcceptedDuration(true);
						resetNegotiationRound(message.getBroker().getId());
						service.getBrokerProxyService().sendMessage(
								message.getBroker(), ca);
						return;
					}
				}

				// Early Withdraw
				if (!message.isAcceptedEarlyWithdrawPayment()
						&& message
								.isDiscussedIssue(ContractIssue.EARLY_WITHDRAW_PRICE)) {
					co = new ContractOffer(message);
					coEarlyWithdrawPrice = generateOfferPriceBuyer(
							initialEarlyExitPrice, reservationEarlyExitPrice,
							getRound(message));
					co.setEarlyWithdrawPayment(coEarlyWithdrawPrice);
					utility = computeEarlyWithdrawUtility(message);
					counterOfferUtility = computeEarlyWithdrawUtility(co);
					log.info("Early Withdraw Eval: " + message
							+ "CounterOffer: " + co + " Round ="
							+ getRound(message) + " Utility=" + utility
							+ "CO-Utility=" + counterOfferUtility);
					// cant find a better option --> ACCEPT
					if (canAccept && utility >= counterOfferUtility) {
						ContractAccept ca = new ContractAccept(message);
						ca.setAcceptedEarlyWithdrawPayment(true);
						resetNegotiationRound(message.getBroker().getId());
						service.getBrokerProxyService().sendMessage(
								message.getBroker(), ca);
						return;
					}
				}

				// NOTHING WAS ACCEPTED THIS ROUND -> COUNTER OFFER
				if (!message.isAcceptedEnergyPrice()
						&& message.isDiscussedIssue(ContractIssue.ENERGY_PRICE)) {
					co = new ContractOffer(message);
					co.setEnergyPrice(coEnergyPrice);
					co.setDiscussedIssue(ContractIssue.ENERGY_PRICE);
					service.getBrokerProxyService().sendMessage(
							message.getBroker(), co);
				} else if (!message.isAcceptedPeakLoadPrice()
						&& message
								.isDiscussedIssue(ContractIssue.PEAK_LOAD_PRICE)) {
					co = new ContractOffer(message);
					co.setPeakLoadPrice(coPeakLoadPrice);
					co.setDiscussedIssue(ContractIssue.PEAK_LOAD_PRICE);
					service.getBrokerProxyService().sendMessage(
							message.getBroker(), co);
				} else if (!message.isAcceptedDuration()
						&& message.isDiscussedIssue(ContractIssue.DURATION)) {
					co = new ContractOffer(message);
					co.setDuration(coDuration);
					co.setDiscussedIssue(ContractIssue.DURATION);
					service.getBrokerProxyService().sendMessage(
							message.getBroker(), co);
				} else if (!message.isAcceptedEarlyWithdrawPayment()
						&& message
								.isDiscussedIssue(ContractIssue.EARLY_WITHDRAW_PRICE)) {
					co = new ContractOffer(message);
					co.setEarlyWithdrawPayment(coEarlyWithdrawPrice);
					co.setDiscussedIssue(ContractIssue.EARLY_WITHDRAW_PRICE);
					service.getBrokerProxyService().sendMessage(
							message.getBroker(), co);
				} else {
					throw new PowerTacException(
							"Customer did not react to an Offer!");
				}

			}
			// seller role
			else if (message.getPowerType() == PowerType.PRODUCTION) {
				// Energy Price
				ContractOffer co = new ContractOffer(message);
				if (!message.isAcceptedEnergyPrice()
						&& message.isDiscussedIssue(ContractIssue.ENERGY_PRICE)) {
					coEnergyPrice = generateOfferPriceSeller(
							initialEnergyPrice, reservationEnergyPrice,
							getRound(message));
					co.setEnergyPrice(coEnergyPrice);
					utility = computeEnergyPriceUtilitySeller(message,
							message.getDuration());
					counterOfferUtility = computeEnergyPriceUtilitySeller(co,
							co.getDuration());
					log.info("Energy Price Eval: " + message + "CounterOffer: "
							+ co + " Round =" + getRound(message) + " Utility="
							+ utility + "CO-Utility=" + counterOfferUtility);
					// cant find a better option --> ACCEPT
					if (canAccept && utility >= counterOfferUtility) {
						ContractAccept ca = new ContractAccept(message);
						ca.setAcceptedEnergyPrice(true);
						resetNegotiationRound(message.getBroker().getId());
						service.getBrokerProxyService().sendMessage(
								message.getBroker(), ca);
						return;
					}
				}

				// Peak Load Price
				if (!message.isAcceptedPeakLoadPrice()
						&& message
								.isDiscussedIssue(ContractIssue.PEAK_LOAD_PRICE)) {
					co = new ContractOffer(message);
					coPeakLoadPrice = generateOfferPriceSeller(
							initialPeakLoadPrice, reservationPeakLoadPrice,
							getRound(message));
					co.setPeakLoadPrice(coPeakLoadPrice);
					utility = computePeakLoadPriceUtilitySeller(message,
							message.getDuration());
					counterOfferUtility = computePeakLoadPriceUtilitySeller(co,
							co.getDuration());
					log.info("Peak Load Eval: " + message + "CounterOffer: "
							+ co + " Round =" + getRound(message) + " Utility="
							+ utility + "CO-Utility=" + counterOfferUtility);
					// cant find a better option --> ACCEPT
					if (canAccept && utility >= counterOfferUtility) {
						ContractAccept ca = new ContractAccept(message);
						ca.setAcceptedPeakLoadPrice(true);
						resetNegotiationRound(message.getBroker().getId());
						service.getBrokerProxyService().sendMessage(
								message.getBroker(), ca);
						return;
					}
				}

				// Duration
				if (!message.isAcceptedDuration()
						&& message.isDiscussedIssue(ContractIssue.DURATION)) {
					co = new ContractOffer(message);
					coDuration = generateDuration(message.getDuration(), durationPreference, maxDurationDeviation, getRound(message));
					co.setDuration(coDuration);
					utility = computeUtility(message, message.getDuration());
					counterOfferUtility = computeUtility(co, co.getDuration());
					log.info("Duration Eval: " + message + "CounterOffer: "
							+ co + " Round =" + getRound(message) + " Utility="
							+ utility + "CO-Utility=" + counterOfferUtility);
					// cant find a better option --> ACCEPT
					if (canAccept && message.getDuration()<= durationPreference + maxDurationDeviation && message.getDuration()>= durationPreference-maxDurationDeviation &&  utility >0 && utility >= counterOfferUtility) {
						ContractAccept ca = new ContractAccept(message);
						ca.setAcceptedDuration(true);
						resetNegotiationRound(message.getBroker().getId());
						service.getBrokerProxyService().sendMessage(
								message.getBroker(), ca);
						return;
					}
				}

				// Early Withdraw
				if (!message.isAcceptedEarlyWithdrawPayment()
						&& message
								.isDiscussedIssue(ContractIssue.EARLY_WITHDRAW_PRICE)) {
					co = new ContractOffer(message);
					coEarlyWithdrawPrice = generateOfferPriceSeller(
							initialEarlyExitPrice, reservationEarlyExitPrice,
							getRound(message));
					co.setEarlyWithdrawPayment(coEarlyWithdrawPrice);
					utility = computeEarlyWithdrawUtility(message);
					counterOfferUtility = computeEarlyWithdrawUtility(co);
					log.info("Early Withdraw Eval: " + message
							+ "CounterOffer: " + co + " Round ="
							+ getRound(message) + " Utility=" + utility
							+ "CO-Utility=" + counterOfferUtility);
					// cant find a better option --> ACCEPT
					if (canAccept && utility >= counterOfferUtility) {
						ContractAccept ca = new ContractAccept(message);
						ca.setAcceptedEarlyWithdrawPayment(true);
						resetNegotiationRound(message.getBroker().getId());
						service.getBrokerProxyService().sendMessage(
								message.getBroker(), ca);
						return;
					}
				}

				// NOTHING WAS ACCEPTED THIS ROUND -> COUNTER OFFER
				if (!message.isAcceptedEnergyPrice()
						&& message.isDiscussedIssue(ContractIssue.ENERGY_PRICE)) {
					co = new ContractOffer(message);
					co.setEnergyPrice(coEnergyPrice);
					co.setDiscussedIssue(ContractIssue.ENERGY_PRICE);
					service.getBrokerProxyService().sendMessage(
							message.getBroker(), co);
				} else if (!message.isAcceptedPeakLoadPrice()
						&& message
								.isDiscussedIssue(ContractIssue.PEAK_LOAD_PRICE)) {
					co = new ContractOffer(message);
					co.setPeakLoadPrice(coPeakLoadPrice);
					co.setDiscussedIssue(ContractIssue.PEAK_LOAD_PRICE);
					service.getBrokerProxyService().sendMessage(
							message.getBroker(), co);
				} else if (!message.isAcceptedDuration()
						&& message.isDiscussedIssue(ContractIssue.DURATION)) {
					co = new ContractOffer(message);
					co.setDuration(coDuration);
					co.setDiscussedIssue(ContractIssue.DURATION);
					service.getBrokerProxyService().sendMessage(
							message.getBroker(), co);
				} else if (!message.isAcceptedEarlyWithdrawPayment()
						&& message
								.isDiscussedIssue(ContractIssue.EARLY_WITHDRAW_PRICE)) {
					co = new ContractOffer(message);
					co.setEarlyWithdrawPayment(coEarlyWithdrawPrice);
					co.setDiscussedIssue(ContractIssue.EARLY_WITHDRAW_PRICE);
					service.getBrokerProxyService().sendMessage(
							message.getBroker(), co);
				} else {
					throw new PowerTacException(
							"Customer did not react to an Offer!");
				}

			}

		}
	}

	private Integer getRound(ContractOffer message) {
		return negotiationRounds.get(message.getBroker().getId());
	}

	public long generateDuration(long offeredDuration, long preferredDuration,
			long maxDurationDeviation, int round) {
		// contract offer is too long period
		if (offeredDuration > preferredDuration) {
			return preferredDuration
					+ (Math.round(negotiationDecisionFunction(0, round, DEADLINE)
							* maxDurationDeviation)/(24*60*60*1000L)) * 24*60*60*1000L; // round on full hours

		}
		// offer is too short period
		else if (preferredDuration > offeredDuration) {
			return preferredDuration
					- (Math.round(negotiationDecisionFunction(0, round, DEADLINE)
							* maxDurationDeviation)/(24*60*60*1000L)) * 24*60*60*1000L; // round on full hours
		} else {
			return offeredDuration;
		}
	}

	public double generateOfferPriceBuyer(double initialPrice,
			double reservationprice, int round) {
		return initialPrice + negotiationDecisionFunction(0, round, DEADLINE)
				* (reservationprice - initialPrice);
	}

	public double generateOfferPriceSeller(double initialPrice,
			double reservationprice, int round) {
		return reservationprice
				+ (1 - negotiationDecisionFunction(0, round, DEADLINE))
				* (initialPrice - reservationprice);
	}

	protected double negotiationDecisionFunction(int k, int round, int deadline) {
		return k + (1 - k)
				* Math.pow((round + 0.) / deadline, 1 / counterOfferFactor);
	}

	private void updateNegotiationRound(long id) {
		if (negotiationRounds.containsKey(id)
				&& negotiationRounds.get(id) <= DEADLINE) {
			negotiationRounds.put(id, negotiationRounds.get(id) + 1);
		} else {
			negotiationRounds.put(id, 1);
		}

	}
	
	private void resetNegotiationRound(long id) {		
			negotiationRounds.put(id, 0);
	}

	// CONFIRM
	public void handleMessage(ContractConfirm message) {
		if (isValidMessage(message)) {
			activeContracts.put(message.getContractId(), service
					.getContractRepo()
					.findContractById(message.getContractId()));
			negotiationRounds.put(message.getBroker().getId(), 0);
		}
	}

	// END
	public void handleMessage(ContractEnd message) {
		log.info("Contract END arrived at Customer.");
		negotiationRounds.put(message.getBroker().getId(), 0);

	}

	public void handleMessage(ContractAccept message) {
		if (isValidMessage(message)) {
			resetNegotiationRound(message.getBroker().getId());
			log.info("Contract ACCEPT arrived at Customer. Sending Confirm.");
			if (message.isEveryIssueAccepted()) {
				ContractConfirm cf = new ContractConfirm(message.getBroker(),
						message);
				service.getBrokerProxyService().sendMessage(
						message.getBroker(), cf);
				negotiationRounds.put(message.getBroker().getId(), 0);
			} else {
				ContractOffer newOffer = new ContractOffer(message);
				// when you start a negotation on a new issue, do it with your own initial prices
				if(!newOffer.isAcceptedDuration()){
					newOffer.setDuration(durationPreference);
				}
				if(!newOffer.isAcceptedEarlyWithdrawPayment()){
					newOffer.setEarlyWithdrawPayment(initialEarlyExitPrice);
				}
				if(!newOffer.isAcceptedEnergyPrice()){
					newOffer.setEnergyPrice(initialEnergyPrice);
				}
				if(!newOffer.isAcceptedPeakLoadPrice()){
					newOffer.setPeakLoadPrice(initialPeakLoadPrice);
				}
				service.getBrokerProxyService().sendMessage(
						message.getBroker(), newOffer);
			}
		}
	}

	/** DECOMMIT is only allowed if this is a producer customer */
	public void handleMessage(ContractDecommit message) {
		if (isValidMessage(message)) {
			log.info("Contract DECOMMIT arrived at Customer.");

			if (this.getCustomerInfo(PowerType.PRODUCTION) != null) {
				activeContracts.remove(message.getContractId());
				ContractConfirm cf = new ContractConfirm(message.getBroker(),
						message);
				service.getBrokerProxyService().sendMessage(
						message.getBroker(), cf);
				negotiationRounds.put(message.getBroker().getId(), 0);
			} else {
				log.error("Trying to DECOMMIT from a consumer customer. This is not possible");
			}
		}
	}

	public double computeUtility(ContractOffer offer, long duration) {
		long durationdays = duration/ (24*60*60*1000L);
		if (offer.getPowerType() == PowerType.CONSUMPTION) {
			return (computeEnergyPriceUtilityBuyer(offer, duration)
					+ computePeakLoadPriceUtilityBuyer(offer, duration)
					+ computeEarlyWithdrawUtility(offer))/durationdays;
		} else if (offer.getPowerType() == PowerType.PRODUCTION) {
			return (computeEnergyPriceUtilitySeller(offer, duration)
					+ computePeakLoadPriceUtilitySeller(offer, duration)
					+ computeEarlyWithdrawUtility(offer))/durationdays;
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
		utility = utility * Math.pow(timeDiscountingFactor, getRound(offer));

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
		utility = utility * Math.pow(timeDiscountingFactor, getRound(offer));

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
		utility = utility * Math.pow(timeDiscountingFactor, getRound(offer));

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
		utility = utility * Math.pow(timeDiscountingFactor, getRound(offer));

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
		utility = utility * Math.pow(timeDiscountingFactor, getRound(offer));
		return utility;
	}

	protected boolean activeContract(DateTime startDate) {
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
		if (!activeContract(service.getTimeslotRepo().currentTimeslot()
				.getStartTime())) {
			for (CustomerInfo ci : service.getCustomerRepo().findByName(
					getName())) {
				ContractAnnounce cann = new ContractAnnounce(ci.getId());// has
																			// to
																			// be
																			// CustomerInfo
																			// ID
				service.getBrokerProxyService().broadcastMessage(cann);
				service.getTimeSeriesRepo().addHistoricLoadTimeSeries(
						ci.getId(), historicLoad);
			}
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
