package com.fanduck.agent

/**
 * 规格 §4.1 的"看见"：检测器认出来的东西怎么变成一句话。
 *
 * 这里只有纯的部分 —— 跑模型的那一半在 `:app`（`CameraSense` 用 ML Kit 的 image labeling，
 * 全程在本机、不联网，像素不出这台手机）。分成两半是为了让"多少分才算看见""取几个""没看见
 * 怎么说"这些能单测。
 */

/** 检测器认出来的一个东西。`name` 已经是给人看的中文（见 `labelName`）。 */
data class Label(val name: String, val confidence: Float)

/** 一句话里最多说几个。说多了模型抓不住重点，也挤占「看到」120 字的上限。 */
const val LABEL_TOP_K = 3

/** 低于这个置信度就不说 —— 宁可说"未识别物体"，也不要拿 51% 的猜测去驱动鸭子走路。 */
const val LABEL_MIN_CONFIDENCE = 0.6f

/**
 * 检测器没认出东西时也用它（`NO_DETECTOR_CAPTION`）。所以"装了检测器但没认出东西"和
 * "根本没装检测器"在事件流里长得一样 —— 对模型来说这两件事没有区别。
 */
/**
 * **置信度不进这句话**。同一幅画面上模型每拍给出的分数都在抖（实测 77% / 73% / 71% / 73%），
 * 写进文字里就等于每 2 秒一条新事件 —— `shouldWriteSeen` 的"文字没变就不写"直接失效，
 * 事件流和 logcat 会被同一幅画面灌满。只留名字，文字就只在**认出的东西变了**的时候变。
 * 分数仍然有用：它决定谁进得了这句话、谁排前面（见上面两个常量）。
 */
fun captionFromLabels(labels: List<Label>): String {
    val seen = labels
        .filter { it.confidence >= LABEL_MIN_CONFIDENCE }
        .sortedByDescending { it.confidence }
        .take(LABEL_TOP_K)
    if (seen.isEmpty()) return NO_DETECTOR_CAPTION
    return "看到 " + seen.joinToString("、") { it.name }
}

/**
 * ML Kit 的标签是英文，这里翻成中文。**只收常见的**：没收录的原样返回英文 ——
 * 模型读得懂英文，比我们自己编一个不准的词好。
 *
 * 键是 ML Kit image labeling 的原始标签（400 多类里最可能被手机拍到的那些）。
 */
fun labelName(raw: String): String = LABEL_ZH[raw] ?: raw

private val LABEL_ZH: Map<String, String> = mapOf(
    // 人
    "Person" to "人", "Human" to "人", "Face" to "脸", "Head" to "头", "Hair" to "头发",
    "Hand" to "手", "Arm" to "胳膊", "Leg" to "腿", "Foot" to "脚", "Eye" to "眼睛",
    "Mouth" to "嘴", "Nose" to "鼻子", "Ear" to "耳朵", "Beard" to "胡子", "Smile" to "笑",
    "Glasses" to "眼镜", "Hat" to "帽子", "Clothing" to "衣服", "Shoe" to "鞋", "Bag" to "包",
    "Backpack" to "背包", "Handbag" to "手提包", "Watch" to "手表",
    // 家具与房间
    "Chair" to "椅子", "Table" to "桌子", "Desk" to "书桌", "Shelf" to "架子", "Couch" to "沙发",
    "Bed" to "床", "Pillow" to "枕头", "Blanket" to "被子", "Curtain" to "窗帘", "Mirror" to "镜子",
    "Lamp" to "灯", "Clock" to "钟", "Window" to "窗户", "Door" to "门", "Wall" to "墙",
    "Floor" to "地板", "Ceiling" to "天花板", "Room" to "房间", "Kitchen" to "厨房",
    "Bathroom" to "卫生间", "Bedroom" to "卧室", "Living room" to "客厅", "Office" to "办公室",
    // 电子
    "Computer" to "电脑", "Laptop" to "笔记本", "Mobile phone" to "手机", "Tablet" to "平板",
    "Television" to "电视", "Camera" to "相机", "Headphones" to "耳机", "Keyboard" to "键盘",
    "Mouse" to "鼠标", "Remote control" to "遥控器", "Speaker" to "音箱", "Printer" to "打印机",
    "Fan" to "风扇",
    // 吃喝
    "Food" to "食物", "Fruit" to "水果", "Vegetable" to "蔬菜", "Meat" to "肉", "Bread" to "面包",
    "Cake" to "蛋糕", "Pizza" to "披萨", "Rice" to "米饭", "Noodle" to "面条", "Soup" to "汤",
    "Egg" to "鸡蛋", "Cheese" to "奶酪", "Milk" to "牛奶", "Coffee" to "咖啡", "Tea" to "茶",
    "Juice" to "果汁", "Water" to "水", "Wine" to "红酒", "Beer" to "啤酒", "Bottle" to "瓶子",
    "Cup" to "杯子", "Mug" to "杯子", "Glass" to "玻璃杯", "Plate" to "盘子", "Bowl" to "碗",
    "Fork" to "叉子", "Knife" to "刀", "Spoon" to "勺子", "Chopsticks" to "筷子",
    // 动物
    "Dog" to "狗", "Cat" to "猫", "Bird" to "鸟", "Fish" to "鱼", "Horse" to "马", "Cow" to "牛",
    "Sheep" to "羊", "Elephant" to "大象", "Bear" to "熊", "Rabbit" to "兔子", "Insect" to "虫子",
    "Butterfly" to "蝴蝶",
    // 外面
    "Sky" to "天空", "Cloud" to "云", "Sun" to "太阳", "Tree" to "树", "Plant" to "植物",
    "Flower" to "花", "Grass" to "草", "Leaf" to "叶子", "Road" to "马路", "Street" to "街道",
    "Building" to "楼", "House" to "房子", "Car" to "汽车", "Bus" to "公交车", "Truck" to "卡车",
    "Bicycle" to "自行车", "Motorcycle" to "摩托车", "Train" to "火车", "Airplane" to "飞机",
    "Boat" to "船",
    // 杂
    "Book" to "书", "Paper" to "纸", "Pen" to "笔", "Pencil" to "铅笔", "Toy" to "玩具",
    "Ball" to "球", "Doll" to "玩偶",
)
