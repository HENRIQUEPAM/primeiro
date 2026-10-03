"""
Suporte de mesa (moldura) para celular - gera o STL de impressao.

Todas as medidas em milimetros.
Referencial: X = largura (0 no centro), Y = profundidade (0 na frente da base),
Z = altura (0 na mesa).

Dimensionado para o Xiaomi Redmi 13C (168 x 78 x 8,09 mm), em retrato ou
paisagem, com o aparelho podendo ser carregado apoiado.
"""
import numpy as np
import trimesh
from shapely.geometry import box as sbox, Polygon
from trimesh.creation import box, extrude_polygon
from trimesh.transformations import rotation_matrix, translation_matrix

# ----------------------------------------------------------------- parametros
ANG  = np.radians(25.0)          # inclinacao do celular em relacao a vertical
SIN, COS = np.sin(ANG), np.cos(ANG)
TAN  = SIN / COS

W_BASE, D_BASE, T_BASE, R_BASE = 110.0, 85.0, 5.0, 5.0   # base
W_FRAME = 100.0                  # largura da moldura e da aba frontal

Y0      = 18.3                   # face frontal da moldura, em Y, na altura Z=T_BASE
CANAL   = 11.5                   # canal: 8,1 mm do aparelho + folga p/ capa fina
Z_PISO  = 18.0                   # altura do piso onde o celular apoia
T_LIP, H_LIP = 5.0, 30.0         # aba frontal: espessura e comprimento na inclinacao
T_SUP, L_SUP = 6.0, 74.0         # moldura: espessura e comprimento na inclinacao

W_JAN, JAN_A, JAN_B, R_JAN = 60.0, 24.0, 62.0, 12.0      # janela da moldura
W_CABO  = 50.0                   # vao central: conector USB-C + alto-falante inferior
T_FIN, Z_FIN = 12.0, 48.0        # nervuras traseiras


def caixa(x0, x1, y0, y1, z0, z1):
    b = box(extents=[x1 - x0, y1 - y0, z1 - z0])
    b.apply_translation([(x0 + x1) / 2, (y0 + y1) / 2, (z0 + z1) / 2])
    return b


R_INC = rotation_matrix(-ANG, [1, 0, 0])                    # referencial inclinado
M_SUP = translation_matrix([0, Y0, T_BASE]) @ R_INC          # moldura -> mundo

# -------------------------------------------------------------------- 1. base
base_poly = sbox(-W_BASE / 2 + R_BASE, R_BASE,
                 W_BASE / 2 - R_BASE, D_BASE - R_BASE).buffer(R_BASE, resolution=16)
base = extrude_polygon(base_poly, T_BASE)

# ---------------------------------------------------------------- 2. moldura
sup = caixa(-W_FRAME / 2, W_FRAME / 2, 0, T_SUP, -3, L_SUP)
sup.apply_transform(M_SUP)

# ----------------------------------------------------------- 3. aba frontal
lip = caixa(-W_FRAME / 2, W_FRAME / 2, -T_LIP, 0, -3, H_LIP)
lip.apply_transform(translation_matrix([0, Y0 - CANAL / COS, T_BASE]) @ R_INC)

# -------------------------------- 4. piso do canal (macico ate Z_PISO)
piso_bruto = caixa(-W_FRAME / 2, W_FRAME / 2, -CANAL, 0, -40, 60)
piso_bruto.apply_transform(M_SUP)
piso = trimesh.boolean.intersection(
    [piso_bruto, caixa(-200, 200, -50, 200, 0, Z_PISO)], engine='manifold')

# --------------------------------------------- 5. nervuras traseiras (2x)
y_back = Y0 + T_SUP / COS                       # face traseira da moldura em Z=T_BASE
y_fin_top = y_back + (Z_FIN - T_BASE) * TAN
OV = 0.8                                        # sobreposicao p/ solido unico
fin = extrude_polygon(Polygon([(y_back - OV, T_BASE - 2),
                               (D_BASE, T_BASE - 2),
                               (y_fin_top - OV, Z_FIN)]), T_FIN)
fin.apply_transform(np.array([[0, 0, 1, 0],      # perfil YZ, extrusao em X
                              [1, 0, 0, 0],
                              [0, 1, 0, 0],
                              [0, 0, 0, 1]], dtype=float))
fin_esq = fin.copy(); fin_esq.apply_translation([-W_FRAME / 2, 0, 0])
fin_dir = fin.copy(); fin_dir.apply_translation([W_FRAME / 2 - T_FIN, 0, 0])

solido = trimesh.boolean.union([base, sup, lip, piso, fin_esq, fin_dir],
                               engine='manifold')

# ---------------------------------------------------- 6. janela da moldura
jan_poly = sbox(-W_JAN / 2 + R_JAN, JAN_A + R_JAN,
                W_JAN / 2 - R_JAN, JAN_B - R_JAN).buffer(R_JAN, resolution=16)
jan = extrude_polygon(jan_poly, 30.0)
jan.apply_transform(M_SUP @ translation_matrix([0, T_SUP + 2, 0])
                    @ rotation_matrix(np.pi / 2, [1, 0, 0]))

# ------------------------------------- 7. vao central para o cabo/conector
cabo = caixa(-W_CABO / 2, W_CABO / 2, -40, -0.5, -30, 31)
cabo.apply_transform(M_SUP)

# ------------------------------------------- 8. apara frente (Y<0) e base (Z<0)
apara_y = caixa(-200, 200, -60, 0, -60, 200)
apara_z = caixa(-200, 200, -60, 200, -60, 0)

peca = trimesh.boolean.difference([solido, jan, cabo, apara_y, apara_z],
                                  engine='manifold')
peca.merge_vertices()
assert peca.is_watertight and peca.body_count == 1, "malha invalida"

print("corpos:", peca.body_count, "| estanque:", peca.is_watertight,
      "| volume cm3:", round(peca.volume / 1000, 1))
print("dimensoes (mm):", np.round(peca.bounds[1] - peca.bounds[0], 1),
      "| Z min:", round(peca.bounds[0][2], 3))
peca.export('/home/user/primeiro/stl/suporte-celular-moldura.stl')
