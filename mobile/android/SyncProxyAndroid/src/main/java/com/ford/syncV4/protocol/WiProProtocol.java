package com.ford.syncV4.protocol;

import com.ford.syncV4.exception.SyncException;
import com.ford.syncV4.exception.SyncExceptionCause;
import com.ford.syncV4.protocol.enums.FrameDataControlFrameType;
import com.ford.syncV4.protocol.enums.FrameType;
import com.ford.syncV4.protocol.enums.FunctionID;
import com.ford.syncV4.protocol.enums.MessageType;
import com.ford.syncV4.protocol.enums.ServiceType;
import com.ford.syncV4.proxy.constants.Names;
import com.ford.syncV4.proxy.constants.ProtocolConstants;
import com.ford.syncV4.session.Session;
import com.ford.syncV4.util.BitConverter;
import com.ford.syncV4.util.logger.Logger;

import java.io.ByteArrayOutputStream;
import java.util.Hashtable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class WiProProtocol extends AbstractProtocol {

    public static final int MTU_SIZE = 1500;

    public static int PROTOCOL_FRAME_HEADER_SIZE = ProtocolConstants.PROTOCOL_FRAME_HEADER_SIZE_DEFAULT;
    public static int MAX_DATA_SIZE = MTU_SIZE - PROTOCOL_FRAME_HEADER_SIZE;

    int hashID = 0;

    protected Hashtable<Byte, Object> _messageLocks = new Hashtable<Byte, Object>();

    private static final String CLASS_NAME = WiProProtocol.class.getSimpleName();
    private static final String FAILURE_PROPAGATING_MSG = "Failure propagating ";
    private static final Hashtable<Integer, MessageFrameAssembler> ASSEMBLER_FOR_MESSAGE_ID =
            new Hashtable<Integer, MessageFrameAssembler>();
    private static final Hashtable<Byte, Hashtable<Integer, MessageFrameAssembler>> ASSEMBLER_FOR_SESSION_ID =
            new Hashtable<Byte, Hashtable<Integer, MessageFrameAssembler>>();

    private final static PriorityBlockingQueue<Runnable> BLOCKING_QUEUE =
            new PriorityBlockingQueue<Runnable>(20, new CompareMessagesPriority());

    /**
     * Executor service to process single frame commands.
     * If the number of threads is greater than the 10, set the the maximum time that excess
     * idle threads will wait for new tasks before terminating to 10 seconds
     */
    private static final ExecutorService MESSAGES_EXECUTOR_SERVICE =
            new ThreadPoolExecutor(1, 10, 10, TimeUnit.SECONDS, BLOCKING_QUEUE);

    private final ProtocolVersion protocolVersion = new ProtocolVersion();
    private final SendProtocolMessageProcessor sendProtocolMessageProcessor =
            new SendProtocolMessageProcessor();

    private ProtocolFrameHeader mCurrentHeader = null;
    // NOTE: To date, not implemented on SYNC
    private int _heartbeatSendInterval_ms = 0;
    // NOTE: To date, not implemented on SYNC
    private int _heartbeatReceiveInterval_ms = 0;
    private boolean mHaveHeader = false;
    private byte[] mHeaderBuf = new byte[PROTOCOL_FRAME_HEADER_SIZE];
    private int mHeaderBufWritePos = 0;
    private byte[] mDataBuf = null;
    private int mDataBufWritePos = 0;
    private int mMessageID = 0;

    public WiProProtocol(IProtocolListener protocolListener) {
        super(protocolListener);
        setProtocolVersion(ProtocolConstants.PROTOCOL_VERSION_MIN);
    }

    public byte getProtocolVersion() {
        return protocolVersion.getCurrentVersion();
    }

    /**
     * <b>This method is for the Test Cases only</b>
     * @param version test protocol version
     */
    public void set_TEST_ProtocolMinVersion(byte version) {
        ProtocolConstants.PROTOCOL_VERSION_MIN = version;
        setProtocolVersion(version);
    }

    /**
     * <b>This method is for the Test Cases only</b>
     * @param version test protocol version
     */
    public void set_TEST_ProtocolMaxVersion(byte version) {
        ProtocolConstants.PROTOCOL_VERSION_MAX = version;
    }

    public void setProtocolVersion(byte version) {
        protocolVersion.setCurrentVersion(version);

        if (protocolVersion.getCurrentVersion() >= ProtocolConstants.PROTOCOL_VERSION_TWO) {
            updateDataStructureToProtocolVersion(version);
        } else {
            Logger.d(CLASS_NAME + " Protocol version:" + protocolVersion.getCurrentVersion());
        }
    }

    public void StartProtocolSession(byte sessionId) {
        Logger.i("Protocol session should start: " + sessionId);
        ProtocolFrameHeader header = ProtocolFrameHeaderFactory.createStartSession(ServiceType.RPC,
                sessionId, getProtocolVersion());
        Logger.d(CLASS_NAME + " Start Protocol Session, protocol ver:" + getProtocolVersion());
        sendFrameToTransport(header);
    }

    @Override
    public void handleProtocolSessionStarted(ServiceType serviceType, byte sessionID, byte version,
                                             String correlationID) {
        super.handleProtocolSessionStarted(serviceType, sessionID, version, correlationID);

        Logger.d(CLASS_NAME + " Protocol Session Started, protocol ver:" + version);
        setProtocolVersion(version);
    }

    public void StartProtocolService(ServiceType serviceType, Session session) throws IllegalArgumentException {
        byte sessionId = session.getSessionId();
        Logger.i("Protocol service should start: " + serviceType);
        if (sessionId == 0) {
            throw new IllegalArgumentException("currentSession id 0 should be used to start " +
                    "currentSession only, provided id:" + sessionId + ", Service:" + serviceType);
        }
        ProtocolFrameHeader header = ProtocolFrameHeaderFactory.createStartSession(serviceType,
                sessionId, getProtocolVersion());
        sendFrameToTransport(header);
    } // end-method

    private void sendStartProtocolSessionACK(ServiceType serviceType, byte sessionID) {
        ProtocolFrameHeader header = ProtocolFrameHeaderFactory.createStartSessionACK(serviceType,
                sessionID, 0x00, getProtocolVersion());
        sendFrameToTransport(header);
    } // end-method

    public void SetHeartbeatSendInterval(int heartbeatSendInterval_ms) {
        _heartbeatSendInterval_ms = heartbeatSendInterval_ms;
    } // end-method

    public void SetHeartbeatReceiveInterval(int heartbeatReceiveInterval_ms) {
        _heartbeatReceiveInterval_ms = heartbeatReceiveInterval_ms;
    } // end-method

    public void EndProtocolService(ServiceType serviceType, byte sessionID) {
        byte[] data = BitConverter.intToByteArray(hashID);
        ProtocolFrameHeader header = ProtocolFrameHeaderFactory.createEndSession(
                serviceType, sessionID, hashID, getProtocolVersion(), data.length);
        handleProtocolFrameToSend(header, data, 0, data.length);
    } // end-method

    public void SendMessage(final ProtocolMessage protocolMsg) {
        protocolMsg.setRPCType(ProtocolMessage.RPCTYPE_REQUEST); //always sending a request
        final byte sessionID = protocolMsg.getSessionID();
        final byte protocolVersionToSend = getProtocolVersion();

        ProtocolMessageConverter protocolMessageConverter = new ProtocolMessageConverter(
                protocolMsg, protocolVersionToSend).generate();
        final byte[] data = protocolMessageConverter.getData();
        final ServiceType serviceType = protocolMessageConverter.getServiceType();

        // Get the message lock for this protocol currentSession
        Object messageLock = _messageLocks.get(sessionID);
        if (messageLock == null) {
            handleProtocolError("Error sending protocol message to SYNC.",
                    new SyncException("Attempt to send protocol message prior to startSession ACK.",
                            SyncExceptionCause.SYNC_UNAVAILALBE)
            );
            return;
        }

        //Logger.d(CLASS_NAME + " TRACE " + serviceType.getValue());
        MESSAGES_EXECUTOR_SERVICE.execute(new RunnableWithPriority(serviceType.getValue(),
                                                                   protocolMsg.getCorrID()) {

                  @Override
                  public void run() {

                      sendProtocolMessageProcessor.process(serviceType, protocolVersionToSend, data,
                          MAX_DATA_SIZE, sessionID, getNextMessageId(), protocolMsg.getCorrID(),
                          new SendProtocolMessageProcessor.ISendProtocolMessageProcessor() {
                              @Override
                              public void onProtocolFrameToSend(ProtocolFrameHeader header, byte[] data,
                                                                int offset, int length) {
                                  handleProtocolFrameToSend(header, data, offset, length);
                              }
                          }
                      );
                  }
              }
        );
    }

    private void sendFrameToTransport(ProtocolFrameHeader header) {
        handleProtocolFrameToSend(header, null, 0, 0);
    }

    public void HandleReceivedBytes(byte[] receivedBytes, int receivedBytesLength) {
        int receivedBytesReadPos = 0;

        Logger.d(CLASS_NAME + " -> Bytes:" + BitConverter.bytesToHex(receivedBytes, 0, receivedBytesLength) +
                ", protocol ver:" + getProtocolVersion());

        Logger.d(CLASS_NAME + " -> protocol ver:" + getProtocolVersion() + " bytes length:" + receivedBytes.length);

        //Check for a version difference
        if (getProtocolVersion() == ProtocolConstants.PROTOCOL_VERSION_ONE) {
            byte parsedProtocolVersion = (byte) (receivedBytes[0] >>> 4);
            Logger.d(CLASS_NAME + " Parsed v:" + parsedProtocolVersion);

            //if (parsedProtocolVersion <= ProtocolConstants.PROTOCOL_VERSION_MAX) {
                setProtocolVersion(parsedProtocolVersion);
            //}

            //Nothing has been read into the buffer and version is 2
            if (parsedProtocolVersion >= ProtocolConstants.PROTOCOL_VERSION_TWO) {
                updateDataStructureToProtocolVersion(parsedProtocolVersion);
                //Buffer has something in it and version is 2
            } else if (parsedProtocolVersion >= ProtocolConstants.PROTOCOL_VERSION_TWO) {
                //safe current state of the buffer and also set the new version
                byte[] tempHeader = mHeaderBuf;
                updateDataStructureToProtocolVersion(parsedProtocolVersion);
                mHeaderBuf = tempHeader;
            }
        }

        // If I don't yet know the message size, grab those bytes.
        if (!mHaveHeader) {
            // If I can't get the size, just get the bytes that are there.
            int headerBytesNeeded = mHeaderBuf.length - mHeaderBufWritePos;
            if (receivedBytesLength < headerBytesNeeded) {
                System.arraycopy(receivedBytes, receivedBytesReadPos,
                        mHeaderBuf, mHeaderBufWritePos, receivedBytesLength);
                mHeaderBufWritePos += receivedBytesLength;
                return;
            } else {
                // If I got the size, allocate the buffer
                System.arraycopy(receivedBytes, receivedBytesReadPos,
                        mHeaderBuf, mHeaderBufWritePos, headerBytesNeeded);
                mHeaderBufWritePos += headerBytesNeeded;
                receivedBytesReadPos += headerBytesNeeded;
                mHaveHeader = true;
                mCurrentHeader = ProtocolFrameHeader.parseWiProHeader(mHeaderBuf);
                try {
                    mDataBuf = new byte[mCurrentHeader.getDataSize()];
                } catch (OutOfMemoryError e) {
                    // TODO - some terrible things is going on. mCurrentHeader.getDataSize()
                    // returns awfully big number during unregister - register cycle
                    Logger.e("No memory - no regrets.");
                }
                mDataBufWritePos = 0;
            }
        }

        int bytesLeft = receivedBytesLength - receivedBytesReadPos;
        int bytesNeeded = mDataBuf.length - mDataBufWritePos;

        // If I don't have enough bytes for the message, just grab what's there.

        if (bytesLeft < bytesNeeded) {
            System.arraycopy(receivedBytes, receivedBytesReadPos, mDataBuf,
                    mDataBufWritePos, bytesLeft);
            mDataBufWritePos += bytesLeft;
        } else {
            // Fill the buffer and call the handler!
            System.arraycopy(receivedBytes, receivedBytesReadPos, mDataBuf, mDataBufWritePos, bytesNeeded);
            receivedBytesReadPos += bytesNeeded;

            MessageFrameAssembler assembler = getFrameAssemblerForFrame(mCurrentHeader);
            handleProtocolFrameReceived(mCurrentHeader, mDataBuf, assembler);

            // Reset all class member variables for next frame
            mDataBuf = null;
            mDataBufWritePos = 0;
            mHaveHeader = false;
            mHeaderBuf = new byte[PROTOCOL_FRAME_HEADER_SIZE];
            mCurrentHeader = null;
            mHeaderBufWritePos = 0;

            updateDataStructureToProtocolVersion(getProtocolVersion());

            // If there are any bytes left, recursive.
            int moreBytesLeft = receivedBytesLength - receivedBytesReadPos;
            if (moreBytesLeft > 0) {
                byte[] moreBytes = new byte[moreBytesLeft];
                System.arraycopy(receivedBytes, receivedBytesReadPos,
                        moreBytes, 0, moreBytesLeft);
                HandleReceivedBytes(moreBytes, moreBytesLeft);
            }
        }
    }

    private MessageFrameAssembler getFrameAssemblerForFrame(ProtocolFrameHeader header) {
        Hashtable<Integer, MessageFrameAssembler> hashSessionID =
                ASSEMBLER_FOR_SESSION_ID.get(new Byte(header.getSessionID()));
        if (hashSessionID == null) {
            hashSessionID = new Hashtable<Integer, MessageFrameAssembler>();
            ASSEMBLER_FOR_SESSION_ID.put(new Byte(header.getSessionID()), hashSessionID);
        }

        MessageFrameAssembler ret = ASSEMBLER_FOR_MESSAGE_ID.get(new Integer(header.getMessageID()));
        if (ret == null) {
            ret = new MessageFrameAssembler();
            ASSEMBLER_FOR_MESSAGE_ID.put(new Integer(header.getMessageID()), ret);
        }

        return ret;
    }

    private int getNextMessageId() {
        return ++mMessageID;
    }

    protected class MessageFrameAssembler {
        protected boolean hasFirstFrame = false;
        protected boolean hasSecondFrame = false;
        protected ByteArrayOutputStream accumulator = null;
        protected int totalSize = 0;
        protected int framesRemaining = 0;

        protected void handleFirstDataFrame(ProtocolFrameHeader header, byte[] data) {
            //The message is new, so let's figure out how big it is.
            hasFirstFrame = true;
            totalSize = BitConverter.intFromByteArray(data, 0) - PROTOCOL_FRAME_HEADER_SIZE;
            framesRemaining = BitConverter.intFromByteArray(data, 4);
            accumulator = new ByteArrayOutputStream(totalSize);
        }

        protected void handleSecondFrame(ProtocolFrameHeader header, byte[] data) {
            handleRemainingFrame(header, data);
        }

        protected void handleRemainingFrame(ProtocolFrameHeader header, byte[] data) {
            accumulator.write(data, 0, header.getDataSize());
            notifyIfFinished(header);
        }

        protected void notifyIfFinished(ProtocolFrameHeader header) {
            //if (framesRemaining == 0) {
            if (header.getFrameType() == FrameType.Consecutive && header.getFrameData() == 0x0) {
                ProtocolMessage message = new ProtocolMessage();
                message.setServiceType(header.getServiceType());
                message.setSessionID(header.getSessionID());
                //If it is WiPro 2.0 it must have binary header
                if (header.getVersion() >= ProtocolConstants.PROTOCOL_VERSION_TWO) {
                    BinaryFrameHeader binFrameHeader = BinaryFrameHeader.
                            parseBinaryHeader(accumulator.toByteArray());
                    message.setVersion(header.getVersion());
                    message.setRPCType(binFrameHeader.getRPCType());
                    message.setFunctionID(binFrameHeader.getFunctionID());
                    message.setCorrID(binFrameHeader.getCorrID());
                    if (binFrameHeader.getJsonSize() > 0)
                        message.setData(binFrameHeader.getJsonData());
                    if (binFrameHeader.getBulkData() != null) {
                        message.setBulkData(binFrameHeader.getBulkData());
                    }
                } else {
                    message.setData(accumulator.toByteArray());
                }

                ASSEMBLER_FOR_MESSAGE_ID.remove(header.getMessageID());

                try {
                    handleProtocolMessageReceived(message);
                } catch (Exception excp) {
                    Logger.e(FAILURE_PROPAGATING_MSG + "onProtocolMessageReceived: " + excp.toString(), excp);
                } // end-catch

                hasFirstFrame = false;
                hasSecondFrame = false;
                accumulator = null;
            } // end-if
        } // end-method

        protected void handleMultiFrameMessageFrame(ProtocolFrameHeader header, byte[] data) {
            //if (!hasFirstFrame) {
            //	hasFirstFrame = true;
            if (header.getFrameType() == FrameType.First) {
                handleFirstDataFrame(header, data);
            }

            //} else if (!hasSecondFrame) {
            //	hasSecondFrame = true;
            //	framesRemaining--;
            //	handleSecondFrame(header, data);
            //} else {
            //	framesRemaining--;
            else {
                handleRemainingFrame(header, data);
            }

            //}
        } // end-method

        protected void handleFrame(ProtocolFrameHeader header, byte[] data) {
            if (header.getFrameType().equals(FrameType.Control)) {
                handleControlFrame(header, data);
            } else {
                // Must be a form of data frame (single, first, consecutive, etc.)
                if (header.getFrameType() == FrameType.First
                        || header.getFrameType() == FrameType.Consecutive
                        ) {
                    handleMultiFrameMessageFrame(header, data);
                } else {
                    handleSingleFrameMessageFrame(header, data);
                }
            } // end-if
        } // end-method

        private void handleProtocolHeartbeatACK(ProtocolFrameHeader header,
                                                byte[] data) {
            WiProProtocol.this.handleProtocolHeartbeatACK();
        } // end-method

        private void handleControlFrame(ProtocolFrameHeader header, byte[] data) {
            if (header.getFrameData() == FrameDataControlFrameType.HeartbeatACK.getValue()) {
                handleProtocolHeartbeatACK(header, data);
                // TODO heartbeat messages currently are not handled
            } else if (header.getFrameData() == FrameDataControlFrameType.StartService.getValue()) {
                sendStartProtocolSessionACK(header.getServiceType(), header.getSessionID());
            } else if (header.getFrameData() == FrameDataControlFrameType.StartServiceACK.getValue()) {
                // Use this sessionID to create a message lock
                Object messageLock = _messageLocks.get(header.getSessionID());
                if (messageLock == null) {
                    messageLock = new Object();
                    _messageLocks.put(header.getSessionID(), messageLock);
                }
                //hashID = BitConverter.intFromByteArray(data, 0);
                if (getProtocolVersion() >= ProtocolConstants.PROTOCOL_VERSION_TWO) {
                    hashID = header.getMessageID();
                }
                inspectStartServiceACKHeader(header);
            } else if (header.getFrameData() == FrameDataControlFrameType.StartServiceNACK.getValue()) {
                handleStartServiceNackFrame(header.getServiceType());
            } else if (header.getFrameData() == FrameDataControlFrameType.EndService.getValue()) {
                handleEndSessionFrame(header);
            } else if (header.getFrameData() == FrameDataControlFrameType.EndServiceNACK.getValue()) {
                //Logger.d(CLASS_NAME + " End Service NACK");
            } else if (header.getServiceType().getValue() == ServiceType.Mobile_Nav.getValue() &&
                    header.getFrameData() == FrameDataControlFrameType.MobileNaviACK.getValue()) {
                handleMobileNavAckReceived(header);
            } else if (header.getFrameData() == FrameDataControlFrameType.EndServiceACK.getValue()) {
                handleEndSessionFrame(header);
            } else {
                Logger.w(CLASS_NAME + " Unknown frame data:" + header.getFrameData() + ", service type:" +
                        header.getServiceType());
            }
        } // end-method

        private void inspectStartServiceACKHeader(ProtocolFrameHeader header) {
            if (header.getServiceType().equals(ServiceType.RPC)) {
                handleProtocolSessionStarted(header.getServiceType(),
                        header.getSessionID(), getProtocolVersion(), "");
            } else {
                handleProtocolServiceStarted(header.getServiceType(),
                        header.getSessionID(), getProtocolVersion(), "");
            }
        }

        private void handleMobileNavAckReceived(ProtocolFrameHeader header) {
            _protocolListener.onMobileNavAckReceived(header.getMessageID());
        }

        private void handleSingleFrameMessageFrame(ProtocolFrameHeader header, byte[] data) {
            ProtocolMessage message = new ProtocolMessage();
            if (header.getServiceType() == ServiceType.RPC) {
                message.setMessageType(MessageType.RPC);
            } else if (header.getServiceType() == ServiceType.Bulk_Data) {
                message.setMessageType(MessageType.BULK);
            } // end-if
            message.setServiceType(header.getServiceType());
            message.setSessionID(header.getSessionID());
            //If it is WiPro 2.0 it must have binary header
            if (header.getVersion() >= ProtocolConstants.PROTOCOL_VERSION_TWO) {
                BinaryFrameHeader binFrameHeader = BinaryFrameHeader.parseBinaryHeader(data);
                message.setVersion(header.getVersion());
                message.setRPCType(binFrameHeader.getRPCType());
                message.setFunctionID(binFrameHeader.getFunctionID());
                message.setCorrID(binFrameHeader.getCorrID());
                if (binFrameHeader.getJsonSize() > 0) message.setData(binFrameHeader.getJsonData());
                if (binFrameHeader.getBulkData() != null) {
                    message.setBulkData(binFrameHeader.getBulkData());
                }
            } else {
                message.setData(data);
            }

            ASSEMBLER_FOR_MESSAGE_ID.remove(header.getMessageID());

            if (isAppUnregistered(message)) {
                Logger.i("App is unregistered");
                handleAppUnregistered();
            }

            try {
                handleProtocolMessageReceived(message);
            } catch (Exception ex) {
                Logger.e(FAILURE_PROPAGATING_MSG + "onProtocolMessageReceived: " + ex.toString(), ex);
                handleProtocolError(FAILURE_PROPAGATING_MSG + "onProtocolMessageReceived: ", ex);
            } // end-catch
        } // end-method

        private boolean isAppUnregistered(ProtocolMessage message) {
            return (message.getRPCType() == ProtocolMessage.RPCTYPE_RESPONSE) &&
                    (message.getFunctionID() == FunctionID
                            .getFunctionID(Names.UnregisterAppInterface));
        }

    } // end-class

    private void handleEndSessionFrame(ProtocolFrameHeader header) {
        Logger.d(CLASS_NAME + " EndServiceACKHeader v:" + header.getVersion());
        if (getProtocolVersion() >= ProtocolConstants.PROTOCOL_VERSION_TWO) {
            if (hashID == header.getMessageID()) {
                handleProtocolServiceEnded(header.getServiceType(), header.getSessionID(), "");
            }
        } else {
            handleProtocolServiceEnded(header.getServiceType(), header.getSessionID(), "");
        }
    }

    private void handleStartServiceNackFrame(ServiceType serviceType) {
        _protocolListener.onStartServiceNackReceived(serviceType);
    }

    private void updateDataStructureToProtocolVersion(byte version) {
        Logger.d(CLASS_NAME + " Data structure updated to v:" + version);
        // TODO : Incorporate SSL overhead const
        // Implement here

        switch (version) {
            case ProtocolConstants.PROTOCOL_VERSION_ONE:
                PROTOCOL_FRAME_HEADER_SIZE = ProtocolConstants.PROTOCOL_FRAME_HEADER_SIZE_V_1;
                break;
            default:
                PROTOCOL_FRAME_HEADER_SIZE = ProtocolConstants.PROTOCOL_FRAME_HEADER_SIZE_V_2;
                break;
        }

        MAX_DATA_SIZE = MTU_SIZE - PROTOCOL_FRAME_HEADER_SIZE;
        mHeaderBuf = new byte[PROTOCOL_FRAME_HEADER_SIZE];
    }
}