from bokeh.io import push_notebook, show, output_notebook, output_file, save, export_png
from bokeh.plotting import figure
from bokeh.resources import CDN
from bokeh.embed import file_html
from ipywidgets import widgets
from IPython.display import display
import hail as hl
from IPython.display import display
import abc
import collections

__all__ = [
    'interact_table',
    'interact_matrix_table',
]


def interact_table(ht: hl.Table):
    tab = widgets.Tab()
    tab.children = [recursive_build(t) for t in [ht.globals, ht.row]]
    tab.set_title(0, 'globals')
    tab.set_title(1, 'row')
    tab.selected_index = 1
    display(tab)


def interact_matrix_table(mt: hl.MatrixTable):
    tab = widgets.Tab()
    tab.children = [recursive_build(t) for t in [mt.globals, mt.row, mt.col, mt.entry]]
    tab.set_title(0, 'globals')
    tab.set_title(1, 'row')
    tab.set_title(2, 'col')
    tab.set_title(3, 'entry')
    tab.selected_index = 1
    display(tab)


def format_type(t):
    if not isinstance(t, (hl.tstruct, hl.ttuple)):
        return str(t)
    elif isinstance(t, hl.tstruct):
        return 'struct'
    else:
        return 'tuple'


def format_html(s):
    return '<p style="font-family:courier;white-space:pre;line-height: 115%;">{}</p>'.format(
        str(s).replace('<', '&lt').replace('>', '&gt').replace('\n', '</br>'))


# registry function is (expr, handle) -> Widget

def _get_aggr(expr):
    indices = expr._indices
    src = indices.source
    assert len(indices.axes) > 0
    if isinstance(src, hl.MatrixTable):
        if indices.axes == {'row'}:
            return src.aggregate_rows
        elif indices.axes == {'column'}:
            return src.aggregate_cols
        else:
            assert indices.axes == {'row', 'column'}
            return src.aggregate_entries
    else:
        assert isinstance(src, hl.Table)
        assert indices.axes == {'row'}
        return src.aggregate


def get_aggr(expr):
    if hasattr(expr, '__aggr'):
        return expr.__aggr
    else:
        expr.__aggr = _get_aggr(expr)
        return expr.__aggr


def recursive_build(expr):
    handle = widgets.HTML('', sync=True)

    frames = []
    if isinstance(expr.dtype, hl.tstruct):
        frames.append(widgets.HTML('<big>Entire struct:</big>'))
    if isinstance(expr.dtype, hl.ttuple):
        frames.append(widgets.HTML('<big>Entire tuple:</big>'))

    global actions
    frames.extend(list(map(lambda a: a.build(expr, handle),
                           filter(lambda a: a.supports(expr), actions))))
    frames.append(handle)

    if isinstance(expr.dtype, hl.tstruct):
        frames.append(widgets.HTML('<big>Fields:</big>'))
        acc = widgets.Accordion([recursive_build(x) for x in expr.values()])
        for i, (name, fd) in enumerate(expr.items()):
            acc.set_title(i, f'[ {repr(name)} ]: {format_type(fd.dtype)}')
        acc.selected_index = None
        frames.append(acc)
    elif isinstance(expr.dtype, hl.ttuple):
        frames.append(widgets.HTML('<big>Fields:</big>'))
        acc = widgets.Accordion([recursive_build(x) for x in expr.values()])
        for i, fd in enumerate(expr):
            acc.set_title(i, f'[ {i} ]: {format_type(fd.dtype)}')
        acc.selected_index = None
        frames.append(acc)
    return widgets.VBox(frames)


class SummaryAction(object):
    __metaclass__ = abc.ABCMeta

    @abc.abstractmethod
    def supports_type(self, t: hl.HailType) -> bool:
        ...

    @abc.abstractmethod
    def build(self, expr, handle) -> widgets.Widget:
        ...

    def supports(self, expr) -> bool:
        return self.supports_type(expr.dtype)


class Clear(SummaryAction):
    def supports_type(self, t: hl.HailType):
        return True

    def build(self, expr, handle):
        b = widgets.Button(description='Clear', tooltip='Clear output')

        def compute(b):
            handle.value = ''

        b.on_click(compute)
        return b


class Head(SummaryAction):
    def supports_type(self, t: hl.HailType):
        return True

    def build(self, expr, handle):
        b = widgets.Button(description='Head', tooltip='Show first few values')

        def compute(b):
            r = expr._show(types=False, width=100)
            handle.value = format_html(r)

        b.on_click(compute)
        return b


class AggregationAction(SummaryAction):
    __metaclass__ = abc.ABCMeta

    def supports(self, expr):
        return len(expr._indices.axes) > 0 and self.supports_type(expr.dtype)


class Missingness(AggregationAction):
    def supports_type(self, t: hl.HailType):
        return True

    def build(self, expr, handle):
        b = widgets.Button(description='Missingness', tooltip='Compute missingness')

        def compute(b):
            (n, frac) = get_aggr(expr)((hl.agg.count_where(hl.is_missing(expr)),
                                        hl.agg.fraction(hl.is_missing(expr))))
            if frac is None:
                handle.value = format_html('no non-missing values')
            else:
                handle.value = format_html(f'{n} values ({round(frac * 100, 2)}% of total) are missing')

        b.on_click(compute)
        return b


class Stats(AggregationAction):
    def supports_type(self, t: hl.HailType):
        return t in {hl.tint32, hl.tint64, hl.tfloat32, hl.tfloat64}

    def build(self, expr, handle):
        b = widgets.Button(description='Statistics', tooltip='Compute statistics')

        def compute(b):
            stats = get_aggr(expr)(hl.agg.stats(expr))
            emit = []
            if stats is None:
                emit.append('No non-missing values')
            else:
                for k, v in stats.items():
                    emit.append(f'{k.rjust(6)} | {v}')
            handle.value = format_html('\n'.join(emit))

        b.on_click(compute)
        return b


class Hist(AggregationAction):
    def supports_type(self, t: hl.HailType):
        return t in {hl.tint32, hl.tint64, hl.tfloat32, hl.tfloat64}

    def build(self, expr, handle):
        b = widgets.Button(description='Histogram', tooltip='Plot histogram')
        aggr = get_aggr(expr)

        def compute(b):
            stats = aggr(hl.agg.stats(expr))
            hist = aggr(hl.agg.hist(expr, stats.min, stats.max, 50))
            p = figure(background_fill_color='#EEEEEE')
            p.quad(bottom=0, top=hist.bin_freq,
                   left=hist.bin_edges[:-1], right=hist.bin_edges[1:],
                   line_color='black')
            html_path = 'hist.html'
            save(p, filename=html_path, title='Histogram', resources=CDN)
            handle.value = f'<a href="{html_path}" target="_blank"><big>Link to plot</big></a>'

        b.on_click(compute)
        return b


class Counter(AggregationAction):
    def supports_type(self, t: hl.HailType):
        return t in {hl.tstr, hl.tcall, hl.tint32, hl.tbool}

    def build(self, expr, handle):
        b = widgets.Button(description='Counter', tooltip='Compute counter')

        def compute(b):
            c = collections.Counter(get_aggr(expr)(hl.agg.counter(expr)))
            emit = []
            for k, v in c.most_common():
                emit.append('{} | {}'.format(str(v).rjust(6), repr(k)))
            handle.value = format_html('\n'.join(emit))

        b.on_click(compute)
        return b


class LengthStats(AggregationAction):
    def supports_type(self, t: hl.HailType):
        return t == hl.tstr or isinstance(t, (hl.tarray, hl.tset, hl.tdict))

    def build(self, expr, handle):
        b = widgets.Button(description='Length Stats', tooltip='Compute stats about length')

        def compute(b):
            stats = get_aggr(expr)(hl.agg.stats(hl.len(expr)))
            emit = []
            if stats is None:
                emit.append('No non-missing values')
            else:
                for k, v in stats.items():
                    emit.append(f'{k.rjust(6)} | {v}')
            handle.value = format_html('\n'.join(emit))

        b.on_click(compute)
        return b


class LengthCounter(AggregationAction):
    def supports_type(self, t: hl.HailType):
        return t == hl.tstr or isinstance(t, (hl.tarray, hl.tset, hl.tdict))

    def build(self, expr, handle):
        b = widgets.Button(description='Length Counter', tooltip='Compute counter of length')

        def compute(b):
            c = collections.Counter(get_aggr(expr)(hl.agg.counter(hl.len(expr))))
            emit = []
            for k, v in c.most_common():
                emit.append('{} | {}'.format(str(v).rjust(6), repr(k)))
            handle.value = format_html('\n'.join(emit))

        b.on_click(compute)
        return b


class ElementCounter(AggregationAction):
    def supports_type(self, t: hl.HailType):
        return isinstance(t, (hl.tarray, hl.tset, hl.tdict))

    def build(self, expr, handle):

        if isinstance(expr.dtype, hl.tdict):
            b = widgets.Button(description='Key Counter', tooltip='Compute counter of keys')
            expr = expr.keys()
        else:
            b = widgets.Button(description='Element Counter', tooltip='Compute counter of elements')

        def compute(b):
            from collections import Counter
            c = Counter(get_aggr(expr)(hl.agg.counter(hl.agg.explode(expr))))
            emit = []
            for k, v in c.most_common():
                emit.append('{} | {}'.format(str(v).rjust(6), repr(k)))
            handle.value = format_html('\n'.join(emit))

        b.on_click(compute)
        return b


class ContigCounter(AggregationAction):
    def supports_type(self, t: hl.HailType):
        return isinstance(t, hl.tlocus)

    def build(self, expr, handle):
        b = widgets.Button(description='Contig Counter', tooltip='Compute counter of contigs')

        def compute(b):
            result = get_aggr(expr)(hl.agg.counter(expr.contig))
            rg = expr.dtype.reference_genome
            emit = []
            for contig in rg.contigs:
                if contig in result:
                    emit.append(f'{str(result[contig]).rjust(6)} | {repr(contig)}')
            handle.value = format_html('\n'.join(emit))

        b.on_click(compute)
        return b


class TakeBy(AggregationAction):
    def __init__(self, ascending):
        self.ascending = ascending

    def supports_type(self, t: hl.HailType):
        return t in {hl.tint32, hl.tint64, hl.tfloat32, hl.tfloat64}

    def build(self, expr, handle):
        if self.ascending:
            b = widgets.Button(description='Ten Largest', tooltip='Largest ten values with key')
        else:
            b = widgets.Button(description='Ten Smallest', tooltip='Smallest ten values with key')

        def compute(b):
            src = expr._indices.source
            axes = expr._indices.axes
            if isinstance(src, hl.Table):
                assert axes == {'row'}
                key = src.key
            else:
                assert isinstance(src, hl.MatrixTable)
                if axes == {'row'}:
                    key = src.row_key
                elif axes == {'column'}:
                    key = src.col_key
                else:
                    assert axes == {'row', 'column'}
                    key = hl.struct(**src.row_key, **src.col_key)
            ord = expr
            if self.ascending:
                ord = -ord
            to_take = hl.struct(**key, value=expr)
            result = get_aggr(expr)(hl.agg.take(to_take, 10, ordering=ord))
            s = hl.Table.parallelize(result, to_take.dtype)._show(width=100)
            handle.value = format_html(s)

        b.on_click(compute)
        return b


class FractionNonZero(AggregationAction):
    def supports_type(self, t: hl.HailType):
        return hl.expr.types.is_numeric(t)

    def build(self, expr, handle):
        if expr.dtype == hl.tbool:
            b = widgets.Button(description='Fraction True', tooltip='Fraction of values that are True')
            word = 'True'
        else:
            b = widgets.Button(description='Fraction Non-Zero', tooltip='Fraction of values that are non-zero')
            word = 'non-zero'

        def compute(b):

            n, frac = get_aggr(expr)((hl.agg.count_where(hl.bool(expr)),
                                      hl.agg.fraction(hl.agg.filter(lambda x: hl.is_defined(x), (hl.bool(expr))))))
            if frac is None:
                handle.value = format_html('no non-missing values')
            else:
                handle.value = format_html(f'{n} values ({round(frac * 100, 2)}% of total) are {word}')

        b.on_click(compute)
        return b


actions = [
    Clear(),
    Head(),
    Missingness(),
    Stats(),
    Hist(),
    Counter(),
    LengthStats(),
    LengthCounter(),
    ElementCounter(),
    ContigCounter(),
    TakeBy(ascending=True),
    TakeBy(ascending=False),
    FractionNonZero(),
]
