package io.github.seanchatmangpt.jotp.messaging.channels;

import io.github.seanchatmangpt.jotp.*;
import io.github.seanchatmangpt.jotp.messaging.Message;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runnable example demonstrating the DataTypeChannel pattern for strongly-typed message routing.
 *
 * <p>This example shows:
 * <ul>
 *   <li>Creating a DataTypeChannel router
 *   <li>Registering type-specific handlers for CommandMsg, EventMsg, QueryMsg, and DocumentMsg
 *   <li>Dispatching different message types through the router
 *   <li>Pattern matching on sealed message types
 *   <li>Stateful message processing with JOTP Proc
 * </ul>
 *
 * <p>Output demonstrates that each message type is routed to its correct handler and processed
 * independently.
 */
public class DataTypeChannelExample {

  // ====== State classes for each handler ======

  /**
   * State for handling command messages (synchronous requests with replies).
   */
  static class CommandHandlerState {
    final Queue<String> processedCommands = new ConcurrentLinkedQueue<>();

    CommandHandlerState handleCommand(Message.CommandMsg cmd) {
      String result = String.format(
          "COMMAND[%s]: type=%s, payload=%s",
          cmd.messageId().toString().substring(0, 8),
          cmd.commandType(),
          cmd.payload());
      processedCommands.offer(result);
      System.out.println("  CommandHandler: " + result);
      return this;
    }
  }

  /**
   * State for handling event messages (asynchronous notifications).
   */
  static class EventHandlerState {
    final Queue<String> observedEvents = new ConcurrentLinkedQueue<>();
    int eventCount = 0;

    EventHandlerState handleEvent(Message.EventMsg evt) {
      eventCount++;
      String result = String.format(
          "EVENT[%d]: type=%s, payload=%s",
          eventCount,
          evt.eventType(),
          evt.payload());
      observedEvents.offer(result);
      System.out.println("  EventHandler: " + result);
      return this;
    }
  }

  /**
   * State for handling query messages (information requests).
   */
  static class QueryHandlerState {
    final Queue<String> resolvedQueries = new ConcurrentLinkedQueue<>();

    QueryHandlerState handleQuery(Message.QueryMsg qry) {
      String result = String.format(
          "QUERY[%s]: type=%s, criteria=%s",
          qry.messageId().toString().substring(0, 8),
          qry.queryType(),
          qry.criteria());
      resolvedQueries.offer(result);
      System.out.println("  QueryHandler: " + result);
      return this;
    }
  }

  /**
   * State for handling document messages (large data transfers).
   */
  static class DocumentHandlerState {
    final Queue<String> receivedDocuments = new ConcurrentLinkedQueue<>();

    DocumentHandlerState handleDocument(Message.DocumentMsg doc) {
      String result = String.format(
          "DOCUMENT[%s]: type=%s, bytes=%d",
          doc.messageId().toString().substring(0, 8),
          doc.documentType(),
          doc.documentBytes().length);
      receivedDocuments.offer(result);
      System.out.println("  DocumentHandler: " + result);
      return this;
    }
  }

  /**
   * Run the example.
   */
  public static void main(String[] args) throws InterruptedException {
    System.out.println("=== DataTypeChannel Pattern Example ===\n");

    // Step 1: Create handler processes for each message type
    System.out.println("1. Creating type-specific handler processes...");
    var commandHandler = Proc.spawn(
        new CommandHandlerState(),
        state -> msg -> {
          if (msg instanceof Message.CommandMsg cmd) {
            return state.handleCommand(cmd);
          }
          return state;
        });

    var eventHandler = Proc.spawn(
        new EventHandlerState(),
        state -> msg -> {
          if (msg instanceof Message.EventMsg evt) {
            return state.handleEvent(evt);
          }
          return state;
        });

    var queryHandler = Proc.spawn(
        new QueryHandlerState(),
        state -> msg -> {
          if (msg instanceof Message.QueryMsg qry) {
            return state.handleQuery(qry);
          }
          return state;
        });

    var documentHandler = Proc.spawn(
        new DocumentHandlerState(),
        state -> msg -> {
          if (msg instanceof Message.DocumentMsg doc) {
            return state.handleDocument(doc);
          }
          return state;
        });

    System.out.println("  ✓ CommandHandler spawned");
    System.out.println("  ✓ EventHandler spawned");
    System.out.println("  ✓ QueryHandler spawned");
    System.out.println("  ✓ DocumentHandler spawned\n");

    // Step 2: Create the DataTypeChannel router and register routes
    System.out.println("2. Creating DataTypeChannel router and registering routes...");
    var channel = DataTypeChannel.create();

    DataTypeChannel.addRoute(channel, Message.CommandMsg.class, commandHandler);
    DataTypeChannel.addRoute(channel, Message.EventMsg.class, eventHandler);
    DataTypeChannel.addRoute(channel, Message.QueryMsg.class, queryHandler);
    DataTypeChannel.addRoute(channel, Message.DocumentMsg.class, documentHandler);

    System.out.println("  ✓ CommandMsg routed to CommandHandler");
    System.out.println("  ✓ EventMsg routed to EventHandler");
    System.out.println("  ✓ QueryMsg routed to QueryHandler");
    System.out.println("  ✓ DocumentMsg routed to DocumentHandler\n");

    // Step 3: Dispatch different message types through the router
    System.out.println("3. Dispatching messages through the channel...\n");

    // Dispatch a command
    var createUserCmd = Message.command("CreateUser", "John Doe", null);
    System.out.println("  Sending: " + createUserCmd.commandType());
    DataTypeChannel.dispatch(channel, createUserCmd);

    // Dispatch an event
    var userCreatedEvt = Message.event("UserCreated", "john@example.com");
    System.out.println("  Sending: " + userCreatedEvt.eventType());
    DataTypeChannel.dispatch(channel, userCreatedEvt);

    // Dispatch a query
    var getUserQry = Message.query("GetUserById", 42);
    System.out.println("  Sending: " + getUserQry.queryType());
    DataTypeChannel.dispatch(channel, getUserQry);

    // Dispatch a document
    var userDocDoc = Message.document("UserRecord", "UserDocumentContent".getBytes());
    System.out.println("  Sending: " + userDocDoc.documentType());
    DataTypeChannel.dispatch(channel, userDocDoc);

    // Dispatch more events to show multiple handling
    System.out.println();
    var loginEvt = Message.event("UserLogin", "2024-03-12 10:00:00");
    System.out.println("  Sending: " + loginEvt.eventType());
    DataTypeChannel.dispatch(channel, loginEvt);

    var deleteUserCmd = Message.command("DeleteUser", 42, null);
    System.out.println("  Sending: " + deleteUserCmd.commandType());
    DataTypeChannel.dispatch(channel, deleteUserCmd);

    // Allow async processing to complete
    Thread.sleep(500);

    System.out.println("\n4. Verifying message routing and state accumulation...\n");

    // Verify handler states
    var cmdState = Proc.getState(commandHandler);
    var evtState = Proc.getState(eventHandler);
    var qryState = Proc.getState(queryHandler);
    var docState = Proc.getState(documentHandler);

    System.out.println("CommandHandler processed:");
    cmdState.processedCommands.forEach(cmd -> System.out.println("  - " + cmd));

    System.out.println("EventHandler processed:");
    evtState.observedEvents.forEach(evt -> System.out.println("  - " + evt));

    System.out.println("QueryHandler processed:");
    qryState.resolvedQueries.forEach(qry -> System.out.println("  - " + qry));

    System.out.println("DocumentHandler processed:");
    docState.receivedDocuments.forEach(doc -> System.out.println("  - " + doc));

    // Get channel metrics
    var channelState = DataTypeChannel.getState(channel);
    System.out.println("\nChannel Statistics:");
    System.out.println("  Total dispatched: " + channelState.getTotalDispatched());
    System.out.println("  CommandMsg count: " + channelState.getDispatchCount(
        Message.CommandMsg.class));
    System.out.println("  EventMsg count: " + channelState.getDispatchCount(
        Message.EventMsg.class));
    System.out.println("  QueryMsg count: " + channelState.getDispatchCount(
        Message.QueryMsg.class));
    System.out.println("  DocumentMsg count: " + channelState.getDispatchCount(
        Message.DocumentMsg.class));
    System.out.println("  Registered types: " + channelState.registeredTypes().size());

    System.out.println("\n=== Example Complete ===");
  }
}
