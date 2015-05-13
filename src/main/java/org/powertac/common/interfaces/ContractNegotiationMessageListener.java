package org.powertac.common.interfaces;

import org.powertac.common.msg.ContractNegotiationMessage;

public interface ContractNegotiationMessageListener {
	
	public void onMessage(ContractNegotiationMessage msg);

}
