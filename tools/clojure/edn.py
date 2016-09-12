def to_edn(py):
    if isinstance(py, (list, tuple)):
        return '[' + ' '.join(map(to_edn, py)) + ']'
    if isinstance(py, dict):
        return '{' + ' '.join([to_edn(v) for kv in py.iteritems() for v in kv ]) + '}'

    if isinstance(py, bool):
        return str(py).lower()

    if isinstance(py, (int, float)):
        return str(py)

    assert isinstance(py, (str, unicode))
    if py.startswith(':'):
        return py
    if py.startswith("'"):
        return py[1:]
    return '"%s"' % (py.encode('string_escape').replace('"', '\\"'))

def to_edn_arg(py):
    return "'" + to_edn(py).replace("'", "'\\''") + "'"
