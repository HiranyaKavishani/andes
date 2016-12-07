/*
 * Copyright (c) 2016, WSO2 Inc. (http://wso2.com) All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wso2.andes.kernel.dtx;

import org.wso2.andes.kernel.AndesAckData;
import org.wso2.andes.kernel.AndesException;
import org.wso2.andes.kernel.AndesMessage;
import org.wso2.andes.kernel.MessagingEngine;
import org.wso2.andes.server.txn.IncorrectDtxStateException;
import org.wso2.andes.server.txn.TimeoutDtxException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.transaction.xa.Xid;

public class DtxRegistry {
    private final Map<ComparableXid, DtxBranch> branches = new HashMap<>();

    /**
     * Used to communicate with the message store
     */
    private MessagingEngine messagingEngine;

    /**
     * Default constructor
     *
     * @param messagingEngine messaging engine used to communicate with the message store
     */
    public DtxRegistry(MessagingEngine messagingEngine) {
        this.messagingEngine = messagingEngine;
    }

    public synchronized DtxBranch getBranch(Xid xid) {
        return branches.get(new ComparableXid(xid));
    }

    public synchronized boolean registerBranch(DtxBranch branch) {
        ComparableXid xid = new ComparableXid(branch.getXid());
        if(!branches.containsKey(xid)) {
            branches.put(xid, branch);
            return true;
        }
        return false;
    }

    public synchronized void prepare(Xid xid)
            throws UnknownDtxBranchException, IncorrectDtxStateException, TimeoutDtxException, AndesException {
        DtxBranch branch = getBranch(xid);

        if (branch != null) {
            if (!branch.hasAssociatedActiveSessions()) {
                branch.clearAssociations();

                if (branch.expired()) {
                    unregisterBranch(branch);
                    throw new TimeoutDtxException(xid);
                } else if (branch.getState() != DtxBranch.State.ACTIVE
                        && branch.getState() != DtxBranch.State.ROLLBACK_ONLY) {
                    throw new IncorrectDtxStateException("Cannot prepare a transaction in state " + branch.getState(),
                            xid);
                } else {
                    branch.prepare();
                    branch.setState(DtxBranch.State.PREPARED);
                }
            } else {
                throw new IncorrectDtxStateException("Branch still has associated sessions", xid);
            }
        } else {
            throw new UnknownDtxBranchException(xid);
        }
    }

    private boolean unregisterBranch(DtxBranch branch) {
        return (branches.remove(new ComparableXid(branch.getXid())) != null);
    }

    public void storeRecords(Xid xid, List<AndesMessage> enqueueRecords, List<AndesAckData> dequeueRecords)
            throws AndesException {
        messagingEngine.storeDtxRecords(xid, enqueueRecords, dequeueRecords);
    }

    private static final class ComparableXid {
        private final Xid xid;

        private ComparableXid(Xid xid) {
            this.xid = xid;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            ComparableXid that = (ComparableXid) o;

            return compareBytes(xid.getBranchQualifier(), that.xid.getBranchQualifier()) && compareBytes(
                    xid.getGlobalTransactionId(), that.xid.getGlobalTransactionId());
        }

        private static boolean compareBytes(byte[] a, byte[] b) {
            if (a.length != b.length) {
                return false;
            }
            for (int i = 0; i < a.length; i++) {
                if (a[i] != b[i]) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = 0;
            for (int i = 0; i < xid.getGlobalTransactionId().length; i++) {
                result = 31 * result + (int) xid.getGlobalTransactionId()[i];
            }
            for (int i = 0; i < xid.getBranchQualifier().length; i++) {
                result = 31 * result + (int) xid.getBranchQualifier()[i];
            }

            return result;
        }
    }
}
