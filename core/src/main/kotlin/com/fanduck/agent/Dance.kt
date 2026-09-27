package com.fanduck.agent

/**
 * 《哈基米》舞蹈。**规格里没有这个** —— §3.5 的动作白名单只有 stop / velocity / gaze / stand /
 * sit，"跳舞"是 emin 要的后加功能（记在 §13）。
 *
 * 曲子是《哈基米》（赛马娘·东海帝皇那段蜂蜜之歌的二创），实测 122 BPM，一拍 492 ms，
 * 八拍一循环。动作照"帝皇舞步"那个感觉编：原地高抬腿踏步、左右倾倒、点头、跟拍张嘴，
 * 身体还有一点左右摇摆和扭腰。腿是这只鸭子唯一的肢体，所以全靠髋/膝/踝 + 头 + 嘴。
 *
 * 这里只有数据 + 插值（纯的，能在 JVM 上测）；播放（MediaPlayer + 界面时钟）在 :app 的
 * `DancePlayer`。想改舞步就改 `hakimiDance` 里那张表，不用碰别的。
 */

/** 一拍 492 ms（122 BPM），来自对音频做起音检测 + 自相关的结果。 */
const val HAKIMI_BEAT_MS = 492L

/** 一个关键帧：到点时的 15 个关节角（弧度）+ 身体左右位置和朝向。舞是原地跳的，没有 z。 */
data class DanceKey(
    val atMs: Long,
    val joints: FloatArray,
    val x: Float = 0f,
    val yaw: Float = 0f,
)

/** 八拍一循环的长度（毫秒），循环播放时按它取模。 */
fun danceLoopMs(keys: List<DanceKey>): Long = keys.lastOrNull()?.atMs ?: 0L

/**
 * 某一刻的姿势：相邻关键帧之间插值（smoothstep，这样每一拍是"到位—停顿"的弹跳感，
 * 不是匀速平移）；两端夹住。空表返回站立。
 */
fun danceFrame(keys: List<DanceKey>, atMs: Long): DuckFrame {
    if (keys.isEmpty()) return DuckFrame(STAND.copyOf(), 0f, 0f, 0f)
    if (atMs <= keys.first().atMs) return keys.first().asFrame()
    if (atMs >= keys.last().atMs) return keys.last().asFrame()
    val index = keys.indexOfLast { it.atMs <= atMs }
    val from = keys[index]
    val to = keys[index + 1]
    val t = (atMs - from.atMs).toFloat() / (to.atMs - from.atMs).toFloat()
    val s = smoothstep(t)
    val joints = FloatArray(15) { i -> from.joints[i] + (to.joints[i] - from.joints[i]) * s }
    return DuckFrame(joints, from.x + (to.x - from.x) * s, 0f, from.yaw + (to.yaw - from.yaw) * s)
}

private fun DanceKey.asFrame() = DuckFrame(joints.copyOf(), x, 0f, yaw)

// ---------------------------------------------------------------------------
// 编舞
// ---------------------------------------------------------------------------

/** 抬一条腿：大腿抬起来 + 屈膝 + 踝跟着收，幅度按"高抬腿"给。 */
private const val LIFT_HIP = -0.55f
private const val LIFT_KNEE = 0.95f
private const val LIFT_ANKLE = -0.45f

/** 下蹲幅度：膝屈 + 踝跟着走（落地那一下）。 */
private const val SQUAT_KNEE = 0.30f

/**
 * 八拍一循环的舞步。每一拍一个关键帧，最后一帧 = 第一帧（接回循环，不跳）。
 *
 * 表里第 1 个数是拍号，后面是这一拍的姿势：
 *   倾斜（髋侧摆，正 = 一边）、抬哪条腿、点头、转头、张嘴、身体横移、扭腰、下蹲。
 * 正负方向是在渲染里逐张看出来的（`tools/duckmesh/shot.sh`），不是猜的。
 */
fun hakimiDance(beatMs: Long = HAKIMI_BEAT_MS): List<DanceKey> {
    /** 一拍的姿势：从 STAND 出发叠增量。左右腿的增量永远反号（真机左右腿的站立角就是反的）。 */
    fun pose(
        lean: Float = 0f,
        lift: Int = NO_LIFT,
        headPitch: Float = 0f,
        headYaw: Float = 0f,
        jaw: Float = 0f,
        twist: Float = 0f,
        squat: Float = 0f,
    ): FloatArray {
        val j = STAND.copyOf()
        j[1] += lean
        j[11] -= lean
        j[8] += lean * HEAD_ROLL_WITH_LEAN      // 头跟着侧一点，像猫
        j[0] += twist
        j[10] -= twist
        // 屈膝下蹲：方向和 SIT 一致（坐着时左膝 +1.40、左踝 −0.15，即"折起来"那个方向）
        j[3] += squat
        j[13] -= squat
        j[4] -= squat * 0.5f
        j[14] += squat * 0.5f
        if (lift == LEFT_LEG) {
            j[2] += LIFT_HIP
            j[3] += LIFT_KNEE
            j[4] += LIFT_ANKLE
        }
        if (lift == RIGHT_LEG) {
            j[12] -= LIFT_HIP
            j[13] -= LIFT_KNEE
            j[14] -= LIFT_ANKLE
        }
        j[5] += headPitch * NECK_PITCH_SHARE
        j[6] += headPitch
        j[7] += headYaw
        j[9] = jaw
        return j
    }

    val keys = mutableListOf<DanceKey>()
    fun beat(index: Int, x: Float, yaw: Float, joints: FloatArray) {
        keys += DanceKey(atMs = index * beatMs, joints = joints, x = x, yaw = yaw)
    }

    val step = 0.022f                            // 每拍身体左右挪一点，看得见在"踩"
    val twist = 0.10f

    // 拍 0/2/4/6：抬腿那一下（高抬腿 + 点头 + 张嘴）
    // 拍 1/3/5/7：落地那一下（蹲一点收住，闭嘴）
    beat(0, step, twist, pose(lean = LEAN, lift = RIGHT_LEG, headPitch = NOD, jaw = MOUTH, twist = twist))
    beat(1, 0f, 0f, pose(squat = SQUAT_KNEE))
    beat(2, -step, -twist, pose(lean = -LEAN, lift = LEFT_LEG, headPitch = NOD, jaw = MOUTH, twist = -twist))
    beat(3, 0f, 0f, pose(squat = SQUAT_KNEE))
    beat(4, step, twist, pose(lean = LEAN, lift = RIGHT_LEG, headPitch = NOD, headYaw = HEAD_TURN, jaw = MOUTH, twist = twist))
    beat(5, 0f, 0f, pose(squat = SQUAT_KNEE))
    beat(6, -step, -twist, pose(lean = -LEAN, lift = LEFT_LEG, headPitch = NOD, headYaw = -HEAD_TURN, jaw = MOUTH, twist = -twist))
    // 拍 7：双脚一起蹦一下
    beat(7, 0f, 0f, pose(squat = SQUAT_KNEE * 2f, headPitch = NOD * 1.4f, jaw = MOUTH))
    // 拍 8 = 拍 0：循环点必须一模一样（姿势、位移、扭腰都要接上），播放时按 8 拍取模
    beat(8, step, twist, pose(lean = LEAN, lift = RIGHT_LEG, headPitch = NOD, jaw = MOUTH, twist = twist))

    return keys
}

private const val NO_LIFT = -1
private const val LEFT_LEG = 0
private const val RIGHT_LEG = 1

/** 倾多少：够看得见，又不像要摔。 */
private const val LEAN = 0.20f
private const val NOD = 0.22f
private const val HEAD_TURN = 0.35f
private const val MOUTH = 0.6f
private const val HEAD_ROLL_WITH_LEAN = 0.4f
private const val NECK_PITCH_SHARE = 0.5f
