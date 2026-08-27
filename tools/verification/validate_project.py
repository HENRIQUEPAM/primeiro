#!/usr/bin/env python3
"""Consistencia estatica do projeto Android, sem precisar do SDK.

Nao substitui um build de verdade, mas pega os erros que mais quebram o
primeiro `assembleDebug`: XML malformado, referencia a recurso inexistente,
classe declarada no manifesto que nao existe, e ID de layout usado no
ViewBinding que nao esta no XML.

Uso:  python3 tools/verification/validate_project.py
"""

import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "app/src/main/res")
JAVA = os.path.join(ROOT, "app/src/main/java")
MANIFEST = os.path.join(ROOT, "app/src/main/AndroidManifest.xml")
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

errors = []
checks = 0


def ok(label):
    global checks
    checks += 1
    print(f"  PASS  {label}")


def fail(label, detail=""):
    global checks
    checks += 1
    errors.append(label)
    print(f"  FAIL  {label}  {detail}")


def walk(root, suffix=".xml"):
    for base, _, files in os.walk(root):
        for name in files:
            if name.endswith(suffix):
                yield os.path.join(base, name)


# ---------------------------------------------------------------- XML valido
def check_xml_wellformed():
    print("[1] XML bem formado")
    bad = []
    for path in list(walk(RES)) + [MANIFEST]:
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            bad.append(f"{os.path.relpath(path, ROOT)}: {exc}")
    if bad:
        fail(f"{len(bad)} arquivo(s) XML invalido(s)", "; ".join(bad))
    else:
        ok("todos os XML sao bem formados")


# ------------------------------------------------------- recursos declarados
def declared_resources():
    """Mapeia tipo -> conjunto de nomes definidos."""
    declared = {"string": set(), "color": set(), "style": set(),
                "drawable": set(), "layout": set(), "mipmap": set()}

    for path in walk(os.path.join(RES, "values")):
        root = ET.parse(path).getroot()
        for child in root:
            name = child.get("name")
            if not name:
                continue
            tag = child.tag
            if tag in declared:
                declared[tag].add(name)
            elif tag == "item" and child.get("type") in declared:
                declared[child.get("type")].add(name)

    for folder, kind in (("layout", "layout"), ("drawable", "drawable")):
        directory = os.path.join(RES, folder)
        if os.path.isdir(directory):
            for path in walk(directory):
                declared[kind].add(os.path.basename(path)[:-4])

    for base, _, files in os.walk(RES):
        if os.path.basename(base).startswith("mipmap"):
            for name in files:
                declared["mipmap"].add(name.rsplit(".", 1)[0])
    return declared


REF_RE = re.compile(r'"@(?:\+)?(string|color|style|drawable|layout|mipmap)/([A-Za-z0-9_.]+)"')


def check_resource_refs(declared):
    print("[2] Referencias a recursos em XML")
    missing = []
    for path in list(walk(RES)) + [MANIFEST]:
        text = open(path, encoding="utf-8").read()
        for kind, name in REF_RE.findall(text):
            # @+id nao entra aqui (regex nao captura 'id'), e refs de estilo
            # com ponto sao herancas ("Theme.X") resolvidas pelo parent.
            if name not in declared[kind]:
                missing.append(f"{os.path.relpath(path, ROOT)} -> @{kind}/{name}")
    if missing:
        fail(f"{len(missing)} referencia(s) sem definicao", "; ".join(missing[:8]))
    else:
        ok("toda referencia @string/@color/@drawable/@layout/@mipmap resolve")


# ---------------------------------------- recursos usados no Kotlin via R.*
# O lookbehind exclui `android.R.*`, que e o R do framework e nao do app.
R_RE = re.compile(r"(?<!android\.)\bR\.(string|color|drawable|layout|style|mipmap)\.([A-Za-z0-9_]+)")


def check_kotlin_resource_refs(declared):
    print("[3] Referencias R.* no Kotlin")
    missing = []
    for path in walk(JAVA, ".kt"):
        text = open(path, encoding="utf-8").read()
        for kind, name in R_RE.findall(text):
            if name not in declared[kind]:
                missing.append(f"{os.path.relpath(path, ROOT)} -> R.{kind}.{name}")
    if missing:
        fail(f"{len(missing)} referencia(s) R.* sem definicao", "; ".join(missing[:8]))
    else:
        ok("toda referencia R.* resolve para um recurso declarado")


# -------------------------------------------- classes declaradas no manifesto
def check_manifest_classes():
    print("[4] Classes do manifesto existem")
    root = ET.parse(MANIFEST).getroot()
    app = root.find("application")
    missing = []
    found = 0
    for element in list(app):
        name = element.get(f"{ANDROID_NS}name")
        if not name or not name.startswith("com.portaretrato"):
            continue
        found += 1
        path = os.path.join(JAVA, name.replace(".", "/") + ".kt")
        if not os.path.isfile(path):
            missing.append(f"{element.tag} {name}")
    if missing:
        fail(f"{len(missing)} classe(s) do manifesto sem arquivo", "; ".join(missing))
    else:
        ok(f"as {found} classes declaradas no manifesto existem")


# ------------------------------------------------------ IDs do ViewBinding
def camel(snake):
    head, *rest = snake.split("_")
    return head + "".join(part.capitalize() for part in rest)


def binding_name(layout_file):
    return "".join(part.capitalize() for part in layout_file.split("_")) + "Binding"


def check_view_binding():
    print("[5] IDs usados no ViewBinding existem no layout")
    layouts = {}
    for path in walk(os.path.join(RES, "layout")):
        name = os.path.basename(path)[:-4]
        text = open(path, encoding="utf-8").read()
        ids = set(re.findall(r'android:id="@\+id/([A-Za-z0-9_]+)"', text))
        layouts[binding_name(name)] = (name, {camel(i) for i in ids})

    missing = []
    for path in walk(JAVA, ".kt"):
        text = open(path, encoding="utf-8").read()
        for binding, (layout, ids) in layouts.items():
            if f"{binding}.inflate" not in text:
                continue
            for used in set(re.findall(r"\bbinding\.([A-Za-z0-9_]+)", text)):
                if used in ("root",) or used in ids:
                    continue
                missing.append(f"{os.path.relpath(path, ROOT)}: binding.{used} nao existe em {layout}.xml")
    if missing:
        fail(f"{len(missing)} ID(s) de binding inexistente(s)", "; ".join(missing[:8]))
    else:
        ok("todo binding.<id> corresponde a um android:id do layout")


# -------------------------------------------------- coerencia do version catalog
def strip_comments(src):
    """Remove comentarios de bloco e de linha, e strings literais.

    Sem isso, uma classe apenas MENCIONADA num KDoc conta como uso e gera
    falso positivo — foi o que aconteceu na primeira versao desta checagem.
    """
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    src = re.sub(r"//.*$", "", src, flags=re.M)
    src = re.sub(r'"""..*?"""', '""', src, flags=re.S)
    src = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', src)
    return src


DECL_RE = re.compile(
    r"^(?:internal |private |public )?(?:data |sealed |abstract |open |enum |value )*"
    r"(?:class|object|interface) (\w+)",
    re.M,
)


def check_cross_package_imports():
    """Classe de outro pacote usada sem import.

    Foi exatamente o erro que quebrou um build: HomeActivity (em call.ui) usava
    PrivacyActivity (em ui) sem importar. O compilador so reclama no CI; aqui
    aparece em segundos.
    """
    print("[7] Imports entre pacotes")

    sources = {}
    class_pkg = {}
    for path in walk(JAVA, ".kt"):
        src = open(path, encoding="utf-8").read()
        match = re.search(r"^package (\S+)", src, re.M)
        if not match:
            continue
        pkg = match.group(1)
        sources[path] = (pkg, src)
        for decl in DECL_RE.finditer(src):
            class_pkg.setdefault(decl.group(1), pkg)

    missing = []
    for path, (pkg, src) in sources.items():
        imports = set(re.findall(r"^import (\S+)", src, re.M))
        body = strip_comments(re.sub(r"^(package|import).*$", "", src, flags=re.M))
        for cls, cls_pkg in class_pkg.items():
            if cls_pkg == pkg:
                continue
            if f"{cls_pkg}.{cls}" in imports:
                continue
            # Import com curinga do pacote inteiro.
            if f"{cls_pkg}.*" in imports:
                continue
            if not re.search(rf"\b{cls}\b", body):
                continue
            # Uso totalmente qualificado dispensa import.
            if re.search(rf"{re.escape(cls_pkg)}\.{cls}\b", body):
                continue
            missing.append(f"{os.path.basename(path)} usa {cls} (de {cls_pkg}) sem import")

    if missing:
        fail(f"{len(missing)} classe(s) usada(s) sem import", "; ".join(sorted(set(missing))[:8]))
    else:
        ok("toda classe de outro pacote esta importada")


def check_version_catalog():
    print("[6] Version catalog x build.gradle.kts")
    catalog = open(os.path.join(ROOT, "gradle/libs.versions.toml"), encoding="utf-8").read()
    aliases = set(re.findall(r"^([a-zA-Z0-9\-]+)\s*=\s*\{", catalog, re.M))

    missing = []
    for gradle in ("app/build.gradle.kts", "build.gradle.kts"):
        text = open(os.path.join(ROOT, gradle), encoding="utf-8").read()
        for ref in re.findall(r"libs\.(?:plugins\.)?([a-zA-Z0-9.]+)", text):
            alias = ref.replace(".", "-")
            if alias not in aliases:
                missing.append(f"{gradle} -> libs.{ref}")
    if missing:
        fail(f"{len(missing)} alias(es) ausente(s) no catalogo", "; ".join(missing[:8]))
    else:
        ok("todo libs.* referenciado existe em libs.versions.toml")


def main():
    print("=== Validacao estatica do projeto Android ===\n")
    check_xml_wellformed()
    declared = declared_resources()
    print()
    check_resource_refs(declared)
    print()
    check_kotlin_resource_refs(declared)
    print()
    check_manifest_classes()
    print()
    check_view_binding()
    print()
    check_cross_package_imports()
    print()
    check_version_catalog()
    print()
    if errors:
        print(f"{len(errors)} de {checks} verificacoes FALHARAM")
        sys.exit(1)
    print(f"TODAS AS {checks} VERIFICACOES PASSARAM")


if __name__ == "__main__":
    main()
