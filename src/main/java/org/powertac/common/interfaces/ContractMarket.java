package org.powertac.common.interfaces;

public interface ContractMarket {

	public void forwardCollectedMessages();
	
	 /**
	   * Registers a listener for contract messages.
	   */
	  public void registerContractNegotiationMessageListener (ContractNegotiationMessageListener listener);
}
