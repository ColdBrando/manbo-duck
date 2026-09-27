"""Minimal MJCF reader: enough for microduck's robot_*.xml (bodies, hinge joints,
mesh geoms, materials). Not a general MuJoCo parser.

Conventions (same as MuJoCo):
  * pos/quat are relative to the parent body
  * quat is (w, x, y, z); three.js wants (x, y, z, w) — quat_xyzw() converts
  * angle="radian" is assumed
"""
import numpy as np
import xml.etree.ElementTree as ET

def quat_to_mat(q):  # q = (w, x, y, z)
    w, x, y, z = q
    n = w * w + x * x + y * y + z * z
    if n < 1e-12:
        return np.eye(3)
    s = 2.0 / n
    return np.array([
        [1 - s * (y * y + z * z), s * (x * y - w * z),     s * (x * z + w * y)],
        [s * (x * y + w * z),     1 - s * (x * x + z * z), s * (y * z - w * x)],
        [s * (x * z - w * y),     s * (y * z + w * x),     1 - s * (x * x + y * y)],
    ])

def quat_xyzw(q):
    return (q[1], q[2], q[3], q[0])

def _f(txt, n, default):
    if txt is None:
        return np.array(default, dtype=float)
    v = np.array([float(t) for t in txt.split()], dtype=float)
    assert len(v) == n, f"expected {n} numbers, got {txt!r}"
    return v

class Body:
    def __init__(self, elem, parent):
        self.name = elem.get('name') or ''
        self.parent = parent
        self.pos = _f(elem.get('pos'), 3, (0, 0, 0))
        self.quat = _f(elem.get('quat'), 4, (1, 0, 0, 0))
        self.joints, self.geoms, self.sites, self.children = [], [], [], []
        # transform of this body's frame in the *zero* pose (all joints at 0)
        self.local = np.eye(4)
        self.local[:3, :3] = quat_to_mat(self.quat)
        self.local[:3, 3] = self.pos
        self.world = self.local.copy() if parent is None else parent.world @ self.local
        for child in elem:
            if child.tag == 'joint':
                self.joints.append({
                    'name': child.get('name'),
                    'axis': _f(child.get('axis'), 3, (0, 0, 1)),
                    'range': _f(child.get('range'), 2, (-np.inf, np.inf)),
                })
            elif child.tag == 'geom':
                self.geoms.append({
                    'mesh': child.get('mesh'),
                    'material': child.get('material'),
                    'pos': _f(child.get('pos'), 3, (0, 0, 0)),
                    'quat': _f(child.get('quat'), 4, (1, 0, 0, 0)),
                    'cls': child.get('class') or '',
                })
            elif child.tag == 'site':
                self.sites.append({'name': child.get('name'),
                                   'pos': _f(child.get('pos'), 3, (0, 0, 0))})
            elif child.tag == 'body':
                self.children.append(Body(child, self))
        assert len(self.joints) <= 1, f"{self.name}: multi-joint body not supported"

    def walk(self):
        yield self
        for c in self.children:
            yield from c.walk()

class Mjcf:
    def __init__(self, path):
        root = ET.parse(path).getroot()
        self.dir = path.rsplit('/', 1)[0]
        meshdir = root.find('compiler').get('meshdir') or ''
        self.meshdir = f"{self.dir}/{meshdir}".replace('//', '/')
        self.meshes, self.materials = {}, {}
        for asset in root.findall('asset'):
            for m in asset.findall('mesh'):
                f = m.get('file')
                # MuJoCo defaults a mesh's name to the file basename without extension
                self.meshes[m.get('name') or f.rsplit('/', 1)[-1].rsplit('.', 1)[0]] = f
            for m in asset.findall('material'):
                rgba = [float(t) for t in (m.get('rgba') or '1 1 1 1').split()]
                self.materials[m.get('name')] = rgba
        self.bodies = {}
        for wb in root.findall('worldbody'):
            for b in wb.findall('body'):
                body = Body(b, None)
                for x in body.walk():
                    self.bodies[x.name] = x

    def body(self, name):
        return self.bodies[name]

    def root(self):
        for b in self.bodies.values():
            if b.parent is None:
                return b
        raise RuntimeError('no root body')

    def geom_world(self, body, geom):
        """4x4 of a geom's frame in world (zero pose)."""
        m = np.eye(4)
        m[:3, :3] = quat_to_mat(geom['quat'])
        m[:3, 3] = geom['pos']
        return body.world @ m

    def site_world(self, name):
        for b in self.bodies.values():
            for s in b.sites:
                if s['name'] == name:
                    m = np.eye(4)
                    m[:3, 3] = s['pos']
                    return b.world @ m
        raise KeyError(name)

def axis_angle(axis, angle):
    a = np.asarray(axis, dtype=float)
    n = np.linalg.norm(a)
    if n < 1e-12 or abs(angle) < 1e-12:
        return np.eye(3)
    a = a / n
    c, s = np.cos(angle), np.sin(angle)
    K = np.array([[0, -a[2], a[1]], [a[2], 0, -a[0]], [-a[1], a[0], 0]])
    return np.eye(3) + s * K + (1 - c) * (K @ K)

class Pose:
    """Forward kinematics. angles = {joint_name: radians}; missing joints are 0."""

    def __init__(self, mjcf, angles=None):
        self.mjcf = mjcf
        self.angles = angles or {}
        self.world = {}
        root = mjcf.root()
        self.world[root.name] = root.world
        self._recurse(root)

    def _recurse(self, body):
        for child in body.children:
            local = child.local
            if child.joints:
                joint = child.joints[0]
                rot = np.eye(4)
                rot[:3, :3] = axis_angle(joint['axis'], self.angles.get(joint['name'], 0.0))
                local = local @ rot
            self.world[child.name] = self.world[body.name] @ local
            self._recurse(child)

    def geom_world(self, body_name, geom):
        m = np.eye(4)
        m[:3, :3] = quat_to_mat(geom['quat'])
        m[:3, 3] = geom['pos']
        return self.world[body_name] @ m

    def site_world(self, site_name):
        for body in self.mjcf.bodies.values():
            for s in body.sites:
                if s['name'] == site_name:
                    m = np.eye(4)
                    m[:3, 3] = s['pos']
                    return self.world[body.name] @ m
        raise KeyError(site_name)
