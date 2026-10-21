# GawharaStatus 1.1

بلجن بيعرض حالة السيرفر في روم ديسكورد (رسالة واحدة بتتعدل نفسها):
أونلاين / بيترستر / أوفلاين، وعدد اللاعبين (بشريط تقدم)، الأداء (TPS و MS)، الإصدار، مدة التشغيل، أعلى عدد النهارده، وعناوين Java و Bedrock.

- حط الـ Webhook في `plugins/GawharaStatus/config.yml` وبعدها `/serverstatus reload`.
- الأوامر: `/serverstatus` (تحديث) | `reload` | `reset` (يبعت رسالة جديدة). الصلاحية: `gawharastatus.admin`
- لو ترقّي من نسخة قديمة: امسح `config.yml` القديم عشان الشكل الجديد والإعدادات الجديدة تظهر.
- البناء: JDK 25 + Maven → `mvn clean package` → target/GawharaStatus.jar
- ربط اللوحة (اختياري): حط `panel.api-key` (من API Credentials) في config.yml عشان CPU/RAM/Disk ومدة التشغيل الحقيقية.
