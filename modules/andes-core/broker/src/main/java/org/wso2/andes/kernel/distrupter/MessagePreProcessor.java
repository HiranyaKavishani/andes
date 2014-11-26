/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.andes.kernel.distrupter;

import com.lmax.disruptor.EventHandler;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.andes.amqp.AMQPUtils;
import org.wso2.andes.kernel.*;
import org.wso2.andes.subscription.SubscriptionStore;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * This event processor goes through the ring buffer first and update AndesMessage data event objects.
 * NOTE: Only one instance of this processor should process events from the ring buffer
 */
public class MessagePreProcessor implements EventHandler<InboundEvent> {

    private static final Log log = LogFactory.getLog(MessagePreProcessor.class);
    private final SubscriptionStore subscriptionStore;

    public MessagePreProcessor(SubscriptionStore subscriptionStore) {
        this.subscriptionStore = subscriptionStore;
    }

    @Override
    public void onEvent(InboundEvent inboundEvent, long sequence, boolean endOfBatch ) throws Exception {

        if(InboundEvent.Type.MESSAGE_EVENT == inboundEvent.getEventType()) {
            updateRoutingInformation(inboundEvent);
        }
    }

    /**
     * Route the message to queue/queues of subscribers matching in AMQP way. Hierarchical topic message routing is
     * evaluated here. This will duplicate message for each "subscription destination (not message destination)" at
     * different nodes
     *
     * @param event InboundEvent containing the message list
     */
    private void updateRoutingInformation(InboundEvent event) {

        // NOTE: This is the MESSAGE_EVENT and this is the first processing event for this message published to ring
        // Therefore there should be exactly one message in the list.
        // NO NEED TO CHECK FOR LIST SIZE
        AndesMessage message = event.messageList.get(0);

        // Messages are processed in the order they arrive at ring buffer By this processor.
        // By setting message ID through message pre processor we assure, even in a multi publisher scenario, there is
        // no message id ordering issue at node level.
        setMessageID(message);

        if(log.isDebugEnabled()){
            log.debug("Pre processing message. Message ID " + message.getMetadata().getMessageID());
        }

        if (message.getMetadata().isTopic()) {
            String messageRoutingKey = message.getMetadata().getDestination();
            //get all topic subscriptions in the cluster matching to routing key
            //including hierarchical topic case
            List<AndesSubscription> subscriptionList;
            try {

                // TODO: This call is O(n2). In critical path. Need to improve
                subscriptionList = subscriptionStore.getClusterSubscribersForDestination(messageRoutingKey, true);

                // current message is removed from list and updated cloned messages added later
                event.messageList.clear();
                boolean isMessageRouted = false;
                Set<String> alreadyStoredQueueNames = new HashSet<String>();
                for (AndesSubscription subscription : subscriptionList) {
                    if (!alreadyStoredQueueNames.contains(subscription.getStorageQueueName())) {
                        AndesMessage clonedMessage = cloneAndesMessageMetadataAndContent(message);

                        //Message should be written to storage queue name. This is
                        //determined by destination of the message. So should be
                        //updated (but internal metadata will have topic name as usual)
                        clonedMessage.getMetadata().setStorageQueueName(subscription.getStorageQueueName());

                        if (subscription.isDurable()) {
                            /**
                             * For durable topic subscriptions we must update the routing key
                             * in metadata as well so that they become independent messages
                             * baring subscription bound queue name as the destination
                             */
                            clonedMessage.getMetadata().updateMetadata(subscription.getTargetQueue(),
                                    AMQPUtils.DIRECT_EXCHANGE_NAME);
                        }
                        if (log.isDebugEnabled()) {
                            log.debug("Storing metadata queue " + subscription.getStorageQueueName() + " messageID "
                                    + clonedMessage.getMetadata().getMessageID() + " isTopic");
                        }

                        // add the topic wise cloned message to the events list. Message writers will pick that and
                        // write it.
                        event.messageList.add(clonedMessage);
                        isMessageRouted = true;
                        alreadyStoredQueueNames.add(subscription.getStorageQueueName());
                    }
                }

                // If there is no matching subscriber at the moment there is no point of storing the message
                if (!isMessageRouted) {
                    log.info("Message routing key: " + message.getMetadata().getDestination() + " No routes in " +
                            "cluster. Ignoring Message id " + message.getMetadata().getMessageID());

                    // Only one message in list. Clear it and set to ignore the message by message writers
                    event.setEventType(InboundEvent.Type.IGNORE_EVENT);
                    event.messageList.clear();
                }
            } catch (AndesException e) {
                log.error("Error occurred while processing routing information fot topic message. Routing Key " +
                        messageRoutingKey + ", Message ID " + message.getMetadata().getMessageID());
            }
        } else {
            AndesMessageMetadata messageMetadata = message.getMetadata();
            messageMetadata.setStorageQueueName(messageMetadata.getDestination());
        }
    }

    /**
     * Create a clone of the message
     *
     * @param message message to be cloned
     * @return Cloned reference of AndesMessage
     */
    private AndesMessage cloneAndesMessageMetadataAndContent(AndesMessage message) {
        long newMessageId = MessagingEngine.getInstance().generateNewMessageId();
        AndesMessageMetadata clonedMetadata = message.getMetadata().deepClone(newMessageId);
        AndesMessage clonedMessage = new AndesMessage(clonedMetadata);

        //Duplicate message content
        List<AndesMessagePart> messageParts = message.getContentChunkList();
        for (AndesMessagePart messagePart : messageParts) {
            // TODO test without deep clone
            clonedMessage.addMessagePart(messagePart.deepClone(newMessageId));
        }

        return clonedMessage;

    }

    /**
     * Set Message ID for AndesMessage.
     * @param message messageID
     */
    private void setMessageID(AndesMessage message) {
        long messageId = MessagingEngine.getInstance().generateNewMessageId();
        message.getMetadata().setMessageID(messageId);

        for (AndesMessagePart messagePart: message.getContentChunkList()) {
            messagePart.setMessageID(messageId);
        }
    }
}
