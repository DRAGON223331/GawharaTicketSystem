# GawharaTickets 1.0

اللاعب يكتب `/report <المشكلة>` فيتعمل **Thread جديد** في روم الإدارة على ديسكورد (مش روم جديدة).
الرسالة الأولى فيها بيانات اللاعب (الاسم، المكان، الوضع، Ping، UUID).

## الإعداد
1. Developer Portal > New Application > Bot > Reset Token (انسخ التوكن).
2. فعّل **Message Content Intent** (عشان ردود الإدارة توصل للاعب).
3. أضف البوت للسيرفر بصلاحيات: View Channel, Send Messages, Create Public Threads, Send Messages in Threads, Manage Threads (لقفل التذكرة).
4. حط التوكن في `plugins/GawharaTickets/config.yml` > `discord.bot-token` ثم `/tickets reload`.

## الأوامر
- `/report <رسالة>` (للكل) — يبعت بلاغ.
- `/tickets list | info <id> | reply <id> <رد> | close <id> [سبب] | reload` — صلاحية `gawharatickets.staff`.

أي رد من الإدارة داخل الـ Thread على ديسكورد بيوصل للاعب في اللعبة (كل ~20 ثانية).
البناء: JDK 25 + `mvn clean package` → `target/GawharaTickets.jar`
