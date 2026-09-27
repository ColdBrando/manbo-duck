"""把 microduck 的 MJCF + STL 装配成屏幕关节树（规格 §6.2/§6.3 的坐标系）。

坐标对应（纯旋转，det=+1，**不镜像**）：
    x_screen = +y_mjcf   （真机左侧 → 屏幕 +X）
    y_screen = +z_mjcf   （同一个"上"）
    z_screen = +x_mjcf   （同一个"前"）

不镜像的理由：镜像可以让"下标 0-4 画在 -X"这条旧版外观原样保留，但会同时把 vy 侧移时
髋部倾倒的方向翻过来，屏幕上就和真机相反。两条腿左右对称，画在哪一侧看不出来；倾倒方向
看得出来。所以用真机几何、不镜像 —— 屏幕上的姿态和真机逐帧一致。

关节树用真机的 body 链（yaw → roll → pitch → knee → ankle），不是规格 §6.5 里
"一条腿一个 Group、三个欧拉角写在一起"的写法：真机三个髋轴不在同一点上（yaw 轴与 roll 轴
相距 18 mm），合成一个 Group 会让 roll 绕错点转。规格 §6.2 那张表只规定"下标 → 哪个舵机、
绕哪根轴"，逐级铰链完全满足它，而且更准。

每个铰链的局部坐标系都对齐屏幕轴（不是 MJCF 的 body 坐标系），所以 duck.js 里可以照规格
那样写 `rotation.y = joints[0]`。每个关节绕哪根轴由 AXIS_OF_INDEX 给出，正负号由
joint_signs() 从真机轴线算出来，不是猜的。
"""
import numpy as np

from mjcf import Mjcf, Pose, quat_to_mat

# 规格 §6.2：下标 → MJCF 关节名（None = 真机是闭链，MJCF 里没有这个自由度）
SPEC_JOINTS = [
    'left_hip_yaw', 'left_hip_roll', 'left_hip_pitch', 'left_knee', 'left_ankle',
    'neck_pitch', 'head_pitch', 'head_yaw', 'head_roll', None,
    'right_hip_yaw', 'right_hip_roll', 'right_hip_pitch', 'right_knee', 'right_ankle',
]

# 屏幕关节名（= duck.js 里的铰链名）→ 规格下标
SCREEN_OF_SPEC = [
    'left_hip_yaw', 'left_hip_roll', 'left_hip_pitch', 'left_knee', 'left_ankle',
    'neck', 'head_pitch', 'head_yaw', 'head_roll', 'jaw',
    'right_hip_yaw', 'right_hip_roll', 'right_hip_pitch', 'right_knee', 'right_ankle',
]

# 屏幕关节名 → 绕屏幕哪根轴转（规格 §6.2 的"屏幕铰链"列）
AXIS_OF_HINGE = {
    'left_hip_yaw': 'y', 'left_hip_roll': 'z', 'left_hip_pitch': 'x',
    'left_knee': 'x', 'left_ankle': 'x',
    'neck': 'x', 'head_pitch': 'x', 'head_yaw': 'y', 'head_roll': 'z', 'jaw': 'x',
    'right_hip_yaw': 'y', 'right_hip_roll': 'z', 'right_hip_pitch': 'x',
    'right_knee': 'x', 'right_ankle': 'x',
}

# 屏幕关节树：父 → 子
PARENT = {
    'trunk_base': 'root',
    'left_hip_yaw': 'trunk_base', 'left_hip_roll': 'left_hip_yaw',
    'left_hip_pitch': 'left_hip_roll', 'left_knee': 'left_hip_pitch',
    'left_ankle': 'left_knee',
    'neck': 'trunk_base', 'head_pitch': 'neck', 'head_yaw': 'head_pitch',
    'head_roll': 'head_yaw', 'jaw': 'head_roll',
    'right_hip_yaw': 'trunk_base', 'right_hip_roll': 'right_hip_yaw',
    'right_hip_pitch': 'right_hip_roll', 'right_knee': 'right_hip_pitch',
    'right_ankle': 'right_knee',
}

# 真机 MJCF body → 屏幕关节名
BODY_TO_HINGE = {
    'trunk_base': 'trunk_base',
    'yaw2roll': 'left_hip_yaw', 'hip_l': 'left_hip_roll',
    'upper_leg_left': 'left_hip_pitch', 'leg': 'left_knee', 'ankle_left': 'left_ankle',
    'bearing_roll': 'right_hip_yaw', 'hip_l_2': 'right_hip_roll',
    'upper_leg_right': 'right_hip_pitch', 'leg_2': 'right_knee',
    'ankle_right': 'right_ankle',
    'neck': 'neck', 'neck_pitch': 'head_pitch',
    'yaw_roll_motion': 'head_yaw', 'jaw_soft': 'head_roll',
}

# 铰链 → 它自己那个 MJCF body（鸭嘴没有，见 jaw_pivot）
HINGE_TO_BODY = {v: k for k, v in BODY_TO_HINGE.items()}

# 屏幕坐标基：第 i 行 = 屏幕第 i 根轴在 MJCF 世界系里的方向
SCREEN_ROT = np.array([
    [0.0, 1.0, 0.0],   # 屏幕 +X ← MJCF +y（真机左侧）
    [0.0, 0.0, 1.0],   # 屏幕 +Y ← MJCF +z（上）
    [1.0, 0.0, 0.0],   # 屏幕 +Z ← MJCF +x（前）
])

# 规格 §6.2 的站立角，用来定地面偏移和符号验证
STAND = np.array([0, -0.0873, -0.4579, -0.0049, 0.4530,
                  0.3491, 0.3491, 0, 0, 0,
                  0, 0.0873, 0.4579, 0.0049, -0.4530])

# 内部零件：包在壳里看不见，去掉省体积
SKIP_PARTS = {'pcb__raspberry_pi_zero_2_w', 'elec_rpi_robot_hat_pcb'}

# 鸭嘴（下标 9）：真机是闭链连杆，MJCF 里没有这个关节。这几块是"会张开的嘴"：
# 橙色的下嘴壳 + 软胶嘴 + 下颌。noenoeil（黄圈，是眼睛那一圈）**不**在这里，
# 它长在上壳上，跟着嘴动就成了"眼睛跟着下巴走"。
JAW_PARTS = {'jaw', 'jaw_soft', 'soft_mouth_top', 'bottom_head_shell'}

# 鸭嘴枢轴：嘴部包围盒的后缘 4% / 上缘 25% 处（贴着嘴角的铰点），轴取左右方向。
# 真机那里是连杆，屏幕上只需要一个像样的转轴；这个比例是看着渲染图定的。
JAW_PIVOT = (0.04, 0.25)


def screen_rot4():
    m = np.eye(4)
    m[:3, :3] = SCREEN_ROT
    return m


def to_screen(R, t):
    """MJCF 世界系下的 (R, t) → 屏幕系：R_s = S·R·Sᵀ，t_s = S·t。"""
    return SCREEN_ROT @ R @ SCREEN_ROT.T, SCREEN_ROT @ t


def geom_local(geom):
    m = np.eye(4)
    m[:3, :3] = quat_to_mat(geom['quat'])
    m[:3, 3] = geom['pos']
    return m


def transform(verts, m):
    """(N,3,3) 的三角形 → (N*3,3) 的世界坐标顶点。"""
    v = verts.reshape(-1, 3)
    return v @ m[:3, :3].T + m[:3, 3]


class Assembly:
    def __init__(self, mjcf, tris_of):
        self.mjcf = mjcf
        self.tris_of = tris_of
        self.zero = Pose(mjcf)
        self.parts = self._collect_parts()
        self.offset = self._ground_offset()
        self.hinges = self._hinges()
        self.signs = self.joint_signs()

    # ---------- 零件 ----------

    def _collect_parts(self):
        """展开 MJCF 的 visual geom，按屏幕铰链分组。collision 那批是同一批网格的副本。"""
        rows = []
        for body_name, body in self.mjcf.bodies.items():
            hinge = BODY_TO_HINGE[body_name]
            for geom in body.geoms:
                if geom['mesh'] is None or geom['cls'] != 'visual':
                    continue
                if geom['mesh'] in SKIP_PARTS:
                    continue
                target = hinge
                if hinge == 'head_roll' and geom['mesh'] in JAW_PARTS:
                    target = 'jaw'
                rows.append({'hinge': target, 'body': body_name, 'geom': geom,
                             'mesh': geom['mesh'],
                             'rgba': [float(c) for c in self.mjcf.materials.get(geom['material'], [1, 1, 1, 1])[:3]]})
        return rows

    # ---------- 地面偏移 ----------

    def _ground_offset(self):
        """STAND 站姿下模型最低点，把整只鸭平移到脚底 y=0。

        规格 §6.3 的 root 是地面原点、duck.position.y 恒为 0，所以偏移要烘进几何。
        """
        angles = {SPEC_JOINTS[i]: STAND[i] for i in range(15) if SPEC_JOINTS[i]}
        pose = Pose(self.mjcf, angles)
        low = min(transform(self.tris_of(p['geom']['mesh']),
                            pose.geom_world(p['body'], p['geom']))[:, 2].min()
                  for p in self.parts)
        return np.array([0.0, -low, 0.0])

    # ---------- 关节位置 ----------

    def _hinge_world_mjcf(self, hinge):
        """屏幕铰链在 MJCF 世界系里的位姿（零姿态）。"""
        if hinge == 'root':
            return np.eye(4)
        if hinge == 'jaw':
            m = self.zero.world['jaw_soft'].copy()
            m[:3, 3] = self._jaw_pivot_mjcf()
            return m
        return self.zero.world[HINGE_TO_BODY[hinge]]

    def _jaw_pivot_mjcf(self):
        """嘴部零件在世界系里的包围盒 → 枢轴点（MJCF 系）。"""
        pts = np.concatenate([
            transform(self.tris_of(p['geom']['mesh']), self.zero.geom_world(p['body'], p['geom']))
            for p in self.parts if p['hinge'] == 'jaw'])
        lo, hi = pts.min(0), pts.max(0)
        fx, fz = JAW_PIVOT
        return np.array([lo[0] + fx * (hi[0] - lo[0]),
                         (lo[1] + hi[1]) / 2.0,
                         lo[2] + fz * (hi[2] - lo[2])])

    def _hinges(self):
        """每个屏幕铰链：父、在父坐标系里的位置、规格下标。位置已含地面偏移。

        铰链在静止姿态下只有平移、没有旋转（局部坐标系对齐屏幕轴）。这样 duck.js 里
        rotation.x/y/z 就正好是规格 §6.2 那根轴，而各零件的朝向全部烘在零件自己的
        相对变换里 —— 真机那些 body 的 quat（每根轴的朝向）不需要出现在关节树上。
        """
        s4 = screen_rot4()
        rest = {}
        order = ['root', 'trunk_base'] + [h for h in PARENT if h not in ('root', 'trunk_base')]
        out = {}
        for hinge in order:
            if hinge == 'root':
                rest[hinge] = np.eye(4)
            else:
                w = s4 @ self._hinge_world_mjcf(hinge)
                w[:3, 3] += self.offset
                rest[hinge] = np.eye(4)
                rest[hinge][:3, 3] = w[:3, 3]
            parent = PARENT.get(hinge)
            if parent is None:
                out[hinge] = {'parent': None, 'pos': np.zeros(3), 'index': None, 'axis': None}
                continue
            index = SCREEN_OF_SPEC.index(hinge) if hinge in SCREEN_OF_SPEC else None
            out[hinge] = {'parent': parent, 'pos': rest[hinge][:3, 3] - rest[parent][:3, 3],
                          'index': index, 'axis': AXIS_OF_HINGE.get(hinge)}
        self._rest = rest
        return out

    # ---------- 零件相对铰链的位姿 ----------

    def part_rows(self):
        """零件相对铰链的位姿。注意两边都要换基：顶点在 build 时已经烘过 S（屏幕系），
        所以零件的世界变换是 S·W·Sᵀ（整体共轭），只左乘 S 会转错。"""
        s4 = screen_rot4()
        rows = []
        for p in self.parts:
            w = s4 @ self.zero.geom_world(p['body'], p['geom']) @ s4.T
            w[:3, 3] += self.offset
            rows.append({**p, 'rel': np.linalg.inv(self._rest[p['hinge']]) @ w})
        return rows

    # ---------- 符号 ----------

    def joint_signs(self):
        """每个下标的正负号：真机轴线在屏幕系里指向 +轴 还是 -轴。

        MuJoCo 的关节轴线在 body 局部坐标系里（这些全是 "0 0 1"），乘上 body 的世界旋转
        就是它在世界里的方向。屏幕上铰链是轴对齐的，所以只需比对主导分量的符号。
        """
        signs = {}
        for i, joint in enumerate(SPEC_JOINTS):
            hinge = SCREEN_OF_SPEC[i]
            if joint is None:
                # 鸭嘴：真机是闭链，MJCF 里没有关节，符号只能按"张嘴方向朝下"来定。
                # 真机的下嘴壳在枢轴前方，绕屏幕 +x 正向转会把嘴尖压下去 = 张嘴，
                # 所以是 +1（规格 §6.5 那行 `beak.rotation.x = -joints[9]` 是给方块鸭写的，
                # 方块鸭的嘴从铰点往 +z 伸，符号自然相反）。
                signs[i] = 1
                continue
            body = HINGE_TO_BODY[hinge]
            axis_world = self.zero.world[body][:3, :3] @ self.mjcf.body(body).joints[0]['axis']
            axis_screen = SCREEN_ROT @ axis_world
            k = 'xyz'.index(AXIS_OF_HINGE[hinge])
            assert abs(axis_screen[k]) > 0.99, f"{joint}: 轴不落在屏幕 {AXIS_OF_HINGE[hinge]} 上 {axis_screen}"
            signs[i] = int(np.sign(axis_screen[k]))
        return signs


def load(xml_path):
    mjcf = Mjcf(xml_path)
    import os
    mdir = mjcf.meshdir
    cache = {}

    def tris_of(name):
        if name not in cache:
            cache[name] = read_stl(os.path.join(mdir, mjcf.meshes[name]))
        return cache[name]

    return Assembly(mjcf, tris_of), cache


def read_stl(path):
    """只读二进制 STL（这批文件全是二进制，1048584 字节那几个是导出器的细分上限）。"""
    import struct
    data = open(path, 'rb').read()
    n = struct.unpack('<I', data[80:84])[0]
    assert len(data) == 84 + 50 * n, f"{path}: 不是二进制 STL"
    a = np.frombuffer(data, dtype=np.uint8, count=50 * n, offset=84).reshape(n, 50)
    return a[:, 12:48].copy().view('<f4').reshape(n, 3, 3).astype(np.float64)
