package org.tron.core.net.services;

import static org.mockito.Mockito.mock;
import static org.tron.core.net.message.handshake.HelloMessage.getEndpointFromNode;

import com.google.protobuf.ByteString;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.tron.common.application.TronApplicationContext;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.FileUtil;
import org.tron.common.utils.ReflectUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.consensus.pbft.message.PbftMessage;
import org.tron.core.ChainBaseManager;
import org.tron.core.Constant;
import org.tron.core.capsule.BlockCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.net.P2pEventHandlerImpl;
import org.tron.core.net.TronNetService;
import org.tron.core.net.message.handshake.HelloMessage;
import org.tron.core.net.message.keepalive.PingMessage;
import org.tron.core.net.peer.PeerConnection;
import org.tron.core.net.peer.PeerManager;
import org.tron.p2p.P2pConfig;
import org.tron.p2p.base.Parameter;
import org.tron.p2p.connection.Channel;
import org.tron.p2p.discover.Node;
import org.tron.p2p.utils.NetUtil;
import org.tron.protos.Discover.Endpoint;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.HelloMessage.Builder;

public class HandShakeServiceTest {

  private static TronApplicationContext context;
  private PeerConnection peer;
  private static P2pEventHandlerImpl p2pEventHandler;
  private static ApplicationContext ctx;
  private static String dbPath = "output-message-handler-test";


  @BeforeClass
  public static void init() throws Exception {
    Args.setParam(new String[] {"--output-directory", dbPath, "--debug"},
        Constant.TEST_CONF);
    context = new TronApplicationContext(DefaultConfig.class);
    p2pEventHandler = context.getBean(P2pEventHandlerImpl.class);
    ctx = (ApplicationContext) ReflectUtils.getFieldObject(p2pEventHandler, "ctx");

    TronNetService tronNetService = context.getBean(TronNetService.class);
    Parameter.p2pConfig = new P2pConfig();
    ReflectUtils.setFieldValue(tronNetService, "p2pConfig", Parameter.p2pConfig);
  }

  @AfterClass
  public static void destroy() {
    Args.clearParam();
    context.destroy();
    FileUtil.deleteDir(new File(dbPath));
  }

  @Before
  public void clearPeers() {
    try {
      Field field = PeerManager.class.getDeclaredField("peers");
      field.setAccessible(true);
      field.set(PeerManager.class, Collections.synchronizedList(new ArrayList<>()));
    } catch (NoSuchFieldException | IllegalAccessException e) {
      //ignore
    }
  }

  @Test
  public void testOkHelloMessage()
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    InetSocketAddress a1 = new InetSocketAddress("127.0.0.1", 10001);
    Channel c1 = mock(Channel.class);
    Mockito.when(c1.getInetSocketAddress()).thenReturn(a1);
    Mockito.when(c1.getInetAddress()).thenReturn(a1.getAddress());
    PeerManager.add(ctx, c1);
    peer = PeerManager.getPeers().get(0);

    Method method = p2pEventHandler.getClass()
        .getDeclaredMethod("processMessage", PeerConnection.class, byte[].class);
    method.setAccessible(true);

    //ok
    Node node = new Node(NetUtil.getNodeId(), a1.getAddress().getHostAddress(), null, a1.getPort());
    HelloMessage helloMessage = new HelloMessage(node, System.currentTimeMillis(),
        ChainBaseManager.getChainBaseManager());
    method.invoke(p2pEventHandler, peer, helloMessage.getSendBytes());

    //dup hello message
    peer.setHelloMessageReceive(helloMessage);
    method.invoke(p2pEventHandler, peer, helloMessage.getSendBytes());

    //dup peer
    peer.setHelloMessageReceive(null);
    Mockito.when(c1.isDisconnect()).thenReturn(true);
    method.invoke(p2pEventHandler, peer, helloMessage.getSendBytes());
  }

  @Test
  public void testInvalidHelloMessage() {
    InetSocketAddress a1 = new InetSocketAddress("127.0.0.1", 10001);
    Node node = new Node(NetUtil.getNodeId(), a1.getAddress().getHostAddress(), null, a1.getPort());
    Protocol.HelloMessage.Builder builder =
        getHelloMessageBuilder(node, System.currentTimeMillis(),
            ChainBaseManager.getChainBaseManager());
    //block hash is empty
    try {
      BlockCapsule.BlockId hid = ChainBaseManager.getChainBaseManager().getHeadBlockId();
      Protocol.HelloMessage.BlockId hBlockId = Protocol.HelloMessage.BlockId.newBuilder()
          .setHash(ByteString.copyFrom(new byte[0]))
          .setNumber(hid.getNum())
          .build();
      builder.setHeadBlockId(hBlockId);
      HelloMessage helloMessage = new HelloMessage(builder.build().toByteArray());
      Assert.assertTrue(!helloMessage.valid());
    } catch (Exception e) {
      Assert.fail();
    }
  }

  @Test
  public void testRelayHelloMessage() throws NoSuchMethodException {
    InetSocketAddress a1 = new InetSocketAddress("127.0.0.1", 10001);
    Channel c1 = mock(Channel.class);
    Mockito.when(c1.getInetSocketAddress()).thenReturn(a1);
    Mockito.when(c1.getInetAddress()).thenReturn(a1.getAddress());
    PeerManager.add(ctx, c1);
    peer = PeerManager.getPeers().get(0);

    Method method = p2pEventHandler.getClass()
        .getDeclaredMethod("processMessage", PeerConnection.class, byte[].class);
    method.setAccessible(true);

    //address is empty
    Args.getInstance().fastForward = true;
    clearPeers();
    Node node2 = new Node(NetUtil.getNodeId(), a1.getAddress().getHostAddress(), null, 10002);
    Protocol.HelloMessage.Builder builder =
        getHelloMessageBuilder(node2, System.currentTimeMillis(),
            ChainBaseManager.getChainBaseManager());

    try {
      HelloMessage helloMessage = new HelloMessage(builder.build().toByteArray());
      method.invoke(p2pEventHandler, peer, helloMessage.getSendBytes());
    } catch (Exception e) {
      Assert.fail();
    }
    Args.getInstance().fastForward = false;
  }

  @Test
  public void testLowAndGenesisBlockNum() throws NoSuchMethodException {
    InetSocketAddress a1 = new InetSocketAddress("127.0.0.1", 10001);
    Channel c1 = mock(Channel.class);
    Mockito.when(c1.getInetSocketAddress()).thenReturn(a1);
    Mockito.when(c1.getInetAddress()).thenReturn(a1.getAddress());
    PeerManager.add(ctx, c1);
    peer = PeerManager.getPeers().get(0);

    Method method = p2pEventHandler.getClass()
        .getDeclaredMethod("processMessage", PeerConnection.class, byte[].class);
    method.setAccessible(true);

    Node node2 = new Node(NetUtil.getNodeId(), a1.getAddress().getHostAddress(), null, 10002);

    //lowestBlockNum > headBlockNum
    Protocol.HelloMessage.Builder builder =
        getHelloMessageBuilder(node2, System.currentTimeMillis(),
            ChainBaseManager.getChainBaseManager());
    builder.setLowestBlockNum(ChainBaseManager.getChainBaseManager().getLowestBlockNum() + 1);
    try {
      HelloMessage helloMessage = new HelloMessage(builder.build().toByteArray());
      method.invoke(p2pEventHandler, peer, helloMessage.getSendBytes());
    } catch (Exception e) {
      Assert.fail();
    }

    //genesisBlock is not equal
    builder = getHelloMessageBuilder(node2, System.currentTimeMillis(),
        ChainBaseManager.getChainBaseManager());
    BlockCapsule.BlockId gid = ChainBaseManager.getChainBaseManager().getGenesisBlockId();
    Protocol.HelloMessage.BlockId gBlockId = Protocol.HelloMessage.BlockId.newBuilder()
        .setHash(gid.getByteString())
        .setNumber(gid.getNum() + 1)
        .build();
    builder.setGenesisBlockId(gBlockId);
    try {
      HelloMessage helloMessage = new HelloMessage(builder.build().toByteArray());
      method.invoke(p2pEventHandler, peer, helloMessage.getSendBytes());
    } catch (Exception e) {
      Assert.fail();
    }

    //solidityBlock is small than us, but not contained
    builder = getHelloMessageBuilder(node2, System.currentTimeMillis(),
        ChainBaseManager.getChainBaseManager());
    BlockCapsule.BlockId sid = ChainBaseManager.getChainBaseManager().getSolidBlockId();
    Protocol.HelloMessage.BlockId sBlockId = Protocol.HelloMessage.BlockId.newBuilder()
        .setHash(ByteString.copyFrom(
            ByteArray.fromHex("0304f784e4e7bae517bcab94c3e0c9214fb4ac7ff9d7d5a937d1f40031f87b81")
                .getBytes()))
        .setNumber(sid.getNum())
        .build();
    builder.setSolidBlockId(sBlockId);
    try {
      HelloMessage helloMessage = new HelloMessage(builder.build().toByteArray());
      method.invoke(p2pEventHandler, peer, helloMessage.getSendBytes());
    } catch (Exception e) {
      Assert.fail();
    }
  }

  private Protocol.HelloMessage.Builder getHelloMessageBuilder(Node from, long timestamp,
      ChainBaseManager chainBaseManager) {
    Endpoint fromEndpoint = getEndpointFromNode(from);

    BlockCapsule.BlockId gid = chainBaseManager.getGenesisBlockId();
    Protocol.HelloMessage.BlockId gBlockId = Protocol.HelloMessage.BlockId.newBuilder()
        .setHash(gid.getByteString())
        .setNumber(gid.getNum())
        .build();

    BlockCapsule.BlockId sid = chainBaseManager.getSolidBlockId();
    Protocol.HelloMessage.BlockId sBlockId = Protocol.HelloMessage.BlockId.newBuilder()
        .setHash(sid.getByteString())
        .setNumber(sid.getNum())
        .build();

    BlockCapsule.BlockId hid = chainBaseManager.getHeadBlockId();
    Protocol.HelloMessage.BlockId hBlockId = Protocol.HelloMessage.BlockId.newBuilder()
        .setHash(hid.getByteString())
        .setNumber(hid.getNum())
        .build();
    Builder builder = Protocol.HelloMessage.newBuilder();
    builder.setFrom(fromEndpoint);
    builder.setVersion(Args.getInstance().getNodeP2pVersion());
    builder.setTimestamp(timestamp);
    builder.setGenesisBlockId(gBlockId);
    builder.setSolidBlockId(sBlockId);
    builder.setHeadBlockId(hBlockId);
    builder.setNodeType(chainBaseManager.getNodeType().getType());
    builder.setLowestBlockNum(chainBaseManager.isLiteNode()
        ? chainBaseManager.getLowestBlockNum() : 0);

    return builder;
  }
}
