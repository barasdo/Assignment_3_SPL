# תכנון המשך מימוש פרוטוקול STOMP

## סקירת הבעיה

כרגע, המימוש של `StompMessagingProtocolImpl` חסר מספר רכיבים קריטיים:

1. **בעיית `handleSend`**: אין מנגנון לשלוח הודעות עם `subscription-id` ייחודי לכל לקוח
2. **חסר ניהול subscriptions**: אין מעקב אחר ה-subscriptions של כל לקוח
3. **`SUBSCRIBE` ו-`UNSUBSCRIBE` לא מומשו**: הפונקציות קיימות אבל ריקות
4. **`ConnectionsImpl` חלקי**: המחלקה משתמשת ב-`gamesMap` (שם לא מתאים) אבל אין קישור למזהי subscription

## דרישות פרוטוקול STOMP

לפי מפרט STOMP:
- כאשר לקוח שולח `SUBSCRIBE` עם header `id`, השרת חייב לזכור את ה-`id` הזה
- כאשר השרת שולח `MESSAGE` ללקוח, הוא חייב לכלול header `subscription` עם אותו `id`
- זה מאפשר ללקוח לזהות לאיזה subscription שייכת כל הודעה

---

## שינויים מוצעים

### 1. מחלקה חדשה: `SubscriptionManager`

**מטרה**: לנהל את כל ה-subscriptions של כל הלקוחות במערכת.

**מיקום**: `server/src/main/java/bgu/spl/net/impl/data/SubscriptionManager.java`

**אחריות**:
- מעקב אחר subscriptions לפי connectionId
- מעקב אחר subscriptions לפי topic/channel
- מתן subscription-id ייחודי לכל subscription
- thread-safe operations

**מבנה נתונים מוצע**:
```java
public class SubscriptionManager {
    // Map: connectionId -> Map(subscriptionId -> topic)
    private ConcurrentHashMap<Integer, ConcurrentHashMap<String, String>> clientSubscriptions;
    
    // Map: topic -> Map(connectionId -> subscriptionId)
    private ConcurrentHashMap<String, ConcurrentHashMap<Integer, String>> topicSubscribers;
    
    // Singleton instance
    private static SubscriptionManager instance;
}
```

**פונקציות עיקריות**:
- `subscribe(int connectionId, String topic, String subscriptionId)`: רושם subscription חדש
- `unsubscribe(int connectionId, String subscriptionId)`: מבטל subscription
- `getSubscriptionId(int connectionId, String topic)`: מחזיר את ה-subscription-id עבור לקוח ו-topic
- `getSubscribersForTopic(String topic)`: מחזיר Map של connectionId -> subscriptionId
- `removeAllSubscriptions(int connectionId)`: מנקה את כל ה-subscriptions של לקוח (בעת disconnect)

---

### 2. עדכון `ConnectionsImpl`

**קובץ**: [ConnectionsImpl.java](file:///c:/Users/doron/OneDrive/שולחן%20העבודה/Assignment%203%20SPL/Assignment%203%20SPL/server/src/main/java/bgu/spl/net/srv/ConnectionsImpl.java)

**שינויים נדרשים**:

#### א. שינוי שם משתנים
- `gamesMap` → `topicSubscribers` (שם יותר מתאים)

#### ב. שינוי `send(String channel, T msg)`
במקום לשלוח את אותה הודעה לכולם, צריך:
1. לקבל את רשימת המנויים מ-`SubscriptionManager`
2. לכל מנוי, לבנות MESSAGE frame עם ה-`subscription-id` הספציפי שלו
3. לשלוח הודעה מותאמת אישית לכל לקוח

**הבעיה הנוכחית**:
```java
public void send(String channel, T msg) {
    List<Integer> subscribers = gamesMap.get(channel);
    for (Integer id : subscribers) {
        send(id, msg);  // שולח את אותה הודעה לכולם!
    }
}
```

**פתרון מוצע**:
```java
public void send(String topic, T msg) {
    Map<Integer, String> subscribers = 
        SubscriptionManager.getInstance().getSubscribersForTopic(topic);
    
    for (Map.Entry<Integer, String> entry : subscribers.entrySet()) {
        int connectionId = entry.getKey();
        String subscriptionId = entry.getValue();
        
        // בנה MESSAGE frame עם subscription header
        String messageFrame = buildMessageFrame(topic, subscriptionId, msg);
        send(connectionId, messageFrame);
    }
}
```

#### ג. הוספת פונקציה עוזרת
```java
private String buildMessageFrame(String destination, String subscriptionId, T body) {
    return "MESSAGE\n" +
           "subscription:" + subscriptionId + "\n" +
           "destination:" + destination + "\n" +
           "message-id:" + generateMessageId() + "\n\n" +
           body + "\n\u0000";
}
```

#### ד. עדכון `subscribe` ו-`unsubscribe`
הפונקציות הקיימות צריכות לעבוד עם `SubscriptionManager`:
```java
public void subscribe(String topic, int connectionId, String subscriptionId) {
    SubscriptionManager.getInstance().subscribe(connectionId, topic, subscriptionId);
}

public void unsubscribe(int connectionId, String subscriptionId) {
    SubscriptionManager.getInstance().unsubscribe(connectionId, subscriptionId);
}
```

---

### 3. עדכון `StompMessagingProtocolImpl`

**קובץ**: [StompMessagingProtocolImpl.java](file:///c:/Users/doron/OneDrive/שולחן%20העבודה/Assignment%203%20SPL/Assignment%203%20SPL/server/src/main/java/bgu/spl/net/impl/stomp/StompMessagingProtocolImpl.java)

#### תיקוני באגים קיימים

**בעיה 1 - שורה 44**: לולאה שגויה
```java
// קוד נוכחי (שגוי):
for (int j=i+1; j < lines.length; j++){
    body.append(lines[i]).append("\n");  // צריך להיות lines[j]!
}

// תיקון:
for (int j=i+1; j < lines.length; j++){
    body.append(lines[j]).append("\n");
}
```

**בעיה 2 - שורה 38**: חסר סוגר של while
```java
// צריך להוסיף } אחרי שורה 43
while (i < lines.length && !lines[i].isEmpty()){
    // ...
    i++;
}  // <-- חסר סוגר כאן
```

**בעיה 3 - שורה 69**: חסר סוגריים
```java
case "DISCONNECT":
    handleDisconnect();  // צריך סוגריים!
    break;
```

#### מימוש `handleSubscribe`

```java
private void handleSubscribe(Map<String, String> headers) {
    if (!isLoggedIn) {
        sendError("Unauthorized", "You must login before subscribing", headers);
        return;
    }
    
    String destination = headers.get("destination");
    String subscriptionId = headers.get("id");
    
    if (destination == null || subscriptionId == null) {
        sendError("Malformed SUBSCRIBE", "Missing destination or id header", headers);
        return;
    }
    
    // רישום ה-subscription
    SubscriptionManager.getInstance().subscribe(connectionId, destination, subscriptionId);
    
    // שליחת RECEIPT אם נדרש
    if (headers.containsKey("receipt")) {
        sendReceipt(headers.get("receipt"));
    }
}
```

#### מימוש `handleUnsubscribe`

```java
private void handleUnsubscribe(Map<String, String> headers) {
    if (!isLoggedIn) {
        sendError("Unauthorized", "You must login before unsubscribing", headers);
        return;
    }
    
    String subscriptionId = headers.get("id");
    
    if (subscriptionId == null) {
        sendError("Malformed UNSUBSCRIBE", "Missing id header", headers);
        return;
    }
    
    // ביטול ה-subscription
    SubscriptionManager.getInstance().unsubscribe(connectionId, subscriptionId);
    
    // שליחת RECEIPT אם נדרש
    if (headers.containsKey("receipt")) {
        sendReceipt(headers.get("receipt"));
    }
}
```

#### מימוש `handleSend`

```java
private void handleSend(Map<String, String> headers, String body) {
    if (!isLoggedIn) {
        sendError("Unauthorized", "You must login before sending messages", headers);
        return;
    }
    
    String destination = headers.get("destination");
    
    if (destination == null) {
        sendError("Malformed SEND", "Missing destination header", headers);
        return;
    }
    
    // שליחת ההודעה לכל המנויים על ה-destination
    // ConnectionsImpl ידאג לבנות MESSAGE frame עם subscription-id לכל לקוח
    connections.send(destination, body);
    
    // שליחת RECEIPT אם נדרש
    if (headers.containsKey("receipt")) {
        sendReceipt(headers.get("receipt"));
    }
}
```

#### מימוש `handleDisconnect`

```java
private void handleDisconnect(Map<String, String> headers) {
    if (!isLoggedIn) {
        sendError("Unauthorized", "You must login before disconnecting", headers);
        return;
    }
    
    // שליחת RECEIPT לפני ניתוק
    if (headers.containsKey("receipt")) {
        sendReceipt(headers.get("receipt"));
    }
    
    // ניקוי subscriptions
    SubscriptionManager.getInstance().removeAllSubscriptions(connectionId);
    
    // ניתוק מה-database
    Database.getInstance().logout(connectionId);
    
    // סימון לסיום
    shouldTerminate = true;
    connections.disconnect(connectionId);
}
```

#### עדכון קריאות ל-handlers

```java
switch (frame) {
    case "CONNECT":
        handleConnect(headers);
        break;
    
    case "SEND":
        handleSend(headers, bodyToString);
        break;
    
    case "SUBSCRIBE":
        handleSubscribe(headers);  // עם headers!
        break;
    
    case "UNSUBSCRIBE":
        handleUnsubscribe(headers);  // עם headers!
        break;

    case "DISCONNECT":
        handleDisconnect(headers);  // עם headers!
        break;

    default:
        sendError("Unknown Command", "Unknown STOMP command: " + frame, headers);
}
```

---

### 4. שיקולי Thread Safety

> [!IMPORTANT]
> כל המבנים צריכים להיות thread-safe כי השרת multi-threaded

**במימוש `SubscriptionManager`**:
- שימוש ב-`ConcurrentHashMap` לכל המבנים
- פעולות אטומיות עם `putIfAbsent`, `remove`, `compute`
- synchronized blocks רק במקומות שבהם צריך פעולות מרובות אטומיות

**במימוש `ConnectionsImpl`**:
- כבר משתמש ב-`ConcurrentHashMap` ו-`CopyOnWriteArrayList`
- צריך לוודא שהפונקציה החדשה `buildMessageFrame` לא משתמשת במשאבים משותפים

---

## סדר מימוש מומלץ

1. **צעד 1**: תקן את הבאגים הקיימים ב-`StompMessagingProtocolImpl`
   - תקן את הלולאה בשורה 44
   - תקן את הסוגריים בשורה 38
   - תקן את handleDisconnect בשורה 69

2. **צעד 2**: צור את `SubscriptionManager`
   - מימוש המחלקה עם כל הפונקציות
   - בדיקות thread-safety

3. **צעד 3**: עדכן את `ConnectionsImpl`
   - שנה שמות משתנים
   - מימוש `buildMessageFrame`
   - עדכן `send(String channel, T msg)`
   - עדכן `subscribe` ו-`unsubscribe`

4. **צעד 4**: השלם את `StompMessagingProtocolImpl`
   - מימוש `handleSubscribe`
   - מימוש `handleUnsubscribe`
   - מימוש `handleSend` המלא
   - מימוש `handleDisconnect`

5. **צעד 5**: בדיקות
   - בדוק עם לקוח אחד
   - בדוק עם מספר לקוחות
   - בדוק subscriptions מרובים ללקוח אחד
   - בדוק disconnect ו-cleanup

---

## דוגמת זרימה

### תרחיש: שני לקוחות מנויים על אותו topic

```
Client A (connectionId=1):
  SUBSCRIBE
  id:sub-1
  destination:/topic/game1

Client B (connectionId=2):
  SUBSCRIBE
  id:sub-xyz
  destination:/topic/game1

Client C (connectionId=3):
  SEND
  destination:/topic/game1
  
  Hello everyone!
```

**מה קורה בשרת**:

1. `handleSubscribe` של Client A:
   - `SubscriptionManager.subscribe(1, "/topic/game1", "sub-1")`

2. `handleSubscribe` של Client B:
   - `SubscriptionManager.subscribe(2, "/topic/game1", "sub-xyz")`

3. `handleSend` של Client C:
   - `connections.send("/topic/game1", "Hello everyone!")`
   - `ConnectionsImpl.send` מקבל את המנויים:
     - `{1: "sub-1", 2: "sub-xyz"}`
   - שולח ל-Client A:
     ```
     MESSAGE
     subscription:sub-1
     destination:/topic/game1
     message-id:123
     
     Hello everyone!
     ```
   - שולח ל-Client B:
     ```
     MESSAGE
     subscription:sub-xyz
     destination:/topic/game1
     message-id:124
     
     Hello everyone!
     ```

**שים לב**: כל לקוח מקבל הודעה עם ה-`subscription-id` שלו!

---

## סיכום

הפתרון המוצע כולל:

1. ✅ **מחלקה חדשה** `SubscriptionManager` - מנהלת את כל ה-subscriptions
2. ✅ **עדכון** `ConnectionsImpl` - בונה MESSAGE frames עם subscription-id ייחודי
3. ✅ **השלמת** `StompMessagingProtocolImpl` - מימוש כל ה-handlers
4. ✅ **תיקון באגים** קיימים בקוד
5. ✅ **Thread-safety** - שימוש ב-concurrent data structures

זה יפתור את הבעיה שלך עם שליחת הודעות עם מזהה לקוח ייחודי! 🎯
