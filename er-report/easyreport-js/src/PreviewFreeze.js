// Only ordinary preview tables carry this marker. Print/paged tables never opt in.
export default class PreviewFreeze {
    constructor(container) {
        this.container = container;
        this.table = null;
        this.cells = [];
        this.frame = null;
        this.originalAttributes = {};
        ['tabindex', 'role', 'aria-label'].forEach(name => {
            this.originalAttributes[name] = container.getAttribute(name);
        });
        this.schedule = () => {
            if (this.frame === null) {
                this.frame = window.requestAnimationFrame(() => {
                    this.frame = null;
                    this.refresh();
                });
            }
        };
        window.addEventListener('resize', this.schedule);
        container.addEventListener('load', this.schedule, true);
        if (window.ResizeObserver) {
            this.observer = new window.ResizeObserver(this.schedule);
        }
        if (document.fonts && document.fonts.ready) {
            document.fonts.ready.then(this.schedule);
        }
        this.refresh();
    }

    clearCells() {
        this.cells.forEach(cell => {
            cell.classList.remove('easyreport-frozen-cell', 'easyreport-frozen-row', 'easyreport-frozen-column');
            ['top', 'left', 'background', 'top-outset', 'left-outset'].concat(['top', 'right', 'bottom', 'left'].reduce((names, side) =>
                names.concat(['width', 'style', 'color'].map(part => 'border-' + side + '-' + part)), [])).forEach(name => {
                cell.style.removeProperty('--easyreport-freeze-' + name);
            });
        });
        this.cells = [];
        this.container.style.removeProperty('--easyreport-freeze-header-height');
    }

    deactivate() {
        this.clearCells();
        this.container.classList.remove('easyreport-freeze-viewport');
        this.container.style.removeProperty('--easyreport-freeze-height');
        this.container.style.removeProperty('--easyreport-freeze-margin-left');
        this.container.style.removeProperty('--easyreport-freeze-margin-right');
        Object.keys(this.originalAttributes).forEach(name => {
            const value = this.originalAttributes[name];
            if (value === null) {
                this.container.removeAttribute(name);
            } else {
                this.container.setAttribute(name, value);
            }
        });
    }

    refresh() {
        const container = this.container;
        const table = container.querySelector('table[data-easyreport-table="true"]');
        if (table !== this.table) {
            this.clearCells();
            if (this.observer) this.observer.disconnect();
            this.table = table;
            if (table && this.observer) this.observer.observe(table);
        }
        if (!table || !window.CSS || !window.CSS.supports('position', 'sticky')) {
            this.deactivate();
            return;
        }
        let rowCount = readCount(table.getAttribute('data-freeze-rows'));
        let columnCount = readCount(table.getAttribute('data-freeze-columns'));
        const rows = Array.prototype.slice.call(table.rows);
        // Both axes include complete merged cells. DOM cell indexes are not
        // column indexes when earlier cells span rows or columns.
        rowCount = completeFrozenRows(rows, rowCount);
        const grid = logicalGrid(rows);
        columnCount = completeFrozenColumns(grid, columnCount);
        if (!rowCount && !columnCount) {
            this.deactivate();
            return;
        }
        const scrollTop = container.scrollTop, scrollLeft = container.scrollLeft;
        this.clearCells();
        container.classList.add('easyreport-freeze-viewport');
        const alignment = container.style.cssFloat;
        container.style.setProperty('--easyreport-freeze-margin-left', alignment === 'left' ? '0px' : 'auto');
        container.style.setProperty('--easyreport-freeze-margin-right', alignment === 'right' ? '0px' : 'auto');
        container.setAttribute('tabindex', '0');
        container.setAttribute('role', 'region');
        container.setAttribute('aria-label', /^zh/i.test(window.navigator.language || '') ? '报表滚动区域' : 'Scrollable report');
        const height = Math.max(180, window.innerHeight - Math.max(0, container.getBoundingClientRect().top) - 12);
        container.style.setProperty('--easyreport-freeze-height', height + 'px');

        const rowOffsets = [];
        // Collapsed borders put the first row half a border below the table.
        // Start sticky rows at zero so no strip of scrolling body can peek out.
        const tableTop = rows[0].getBoundingClientRect().top;
        for (let r = 0; r < rowCount; r++) {
            rowOffsets.push(rows[r].getBoundingClientRect().top - tableTop);
        }
        const firstCell = rows[0] && rows[0].cells[0];
        const firstLeft = firstCell ? firstCell.getBoundingClientRect().left : 0;
        let frozenWidth = 0;
        const positions = grid.cells.filter(item => item.r < rowCount || item.c < columnCount).map(item => {
            const rect = item.cell.getBoundingClientRect();
            if (item.c < columnCount) frozenWidth = Math.max(frozenWidth, rect.right - firstLeft);
            return Object.assign({left: rect.left - firstLeft}, item);
        });
        // A narrow/short viewport must still leave space to read scrolling data.
        if (rowCount && rows[rowCount - 1].getBoundingClientRect().bottom - tableTop > container.clientHeight - 48) rowCount = 0;
        if (columnCount && frozenWidth > container.clientWidth - 64) columnCount = 0;
        if (!rowCount && !columnCount) {
            this.deactivate();
            return;
        }
        // Read layout/styles first, then apply sticky positioning in a single pass.
        const pending = [];
        positions.forEach(({cell, r, c, left}) => {
            if (r >= rowCount && c >= columnCount) return;
            const style = window.getComputedStyle(cell);
            let background = style.backgroundColor;
            if (background === 'transparent' || /^rgba\([^)]*,\s*0\)$/.test(background)) background = '#fff';
            const borders = {};
            ['Top', 'Right', 'Bottom', 'Left'].forEach(side => {
                ['Width', 'Style', 'Color'].forEach(part => {
                    borders['border-' + side.toLowerCase() + '-' + part.toLowerCase()] = style['border' + side + part];
                });
            });
            // Borderless templates get a quiet single-pixel freeze boundary.
            // Existing borders retain their original width, color and line style.
            ['bottom', 'right'].forEach(side => {
                const boundary = side === 'bottom' ? rowCount && r + cell.rowSpan === rowCount : columnCount && c + cell.colSpan === columnCount;
                if (boundary && !parseFloat(borders['border-' + side + '-width'])) {
                    borders['border-' + side + '-width'] = '1px';
                    borders['border-' + side + '-style'] = 'solid';
                    borders['border-' + side + '-color'] = '#b8c2cc';
                }
            });
            pending.push({cell, r, c, left, background, borders});
        });
        const headerHeight = rowCount ? rows[rowCount - 1].getBoundingClientRect().bottom - tableTop : 0;
        container.style.setProperty('--easyreport-freeze-header-height', headerHeight + 'px');
        pending.forEach(({cell, r, c, left, background, borders}) => {
            cell.classList.add('easyreport-frozen-cell');
            cell.style.setProperty('--easyreport-freeze-background', background);
            Object.keys(borders).forEach(name => cell.style.setProperty('--easyreport-freeze-' + name, borders[name]));
            // Keep the outermost border inside the viewport when it is stuck;
            // internal shared borders remain centered on their original seam.
            cell.style.setProperty('--easyreport-freeze-top-outset', parseFloat(borders['border-top-width']) * (rowCount && r === 0 ? 0.5 : 1) + 'px');
            cell.style.setProperty('--easyreport-freeze-left-outset', parseFloat(borders['border-left-width']) * (columnCount && c === 0 ? 0.5 : 1) + 'px');
            if (r < rowCount) {
                cell.classList.add('easyreport-frozen-row');
                cell.style.setProperty('--easyreport-freeze-top', rowOffsets[r] + 'px');
            }
            if (c < columnCount) {
                cell.classList.add('easyreport-frozen-column');
                cell.style.setProperty('--easyreport-freeze-left', left + 'px');
            }
            this.cells.push(cell);
        });
        container.scrollTop = scrollTop;
        container.scrollLeft = scrollLeft;
    }
}

function logicalGrid(rows) {
    const cells = [], occupiedUntil = [];
    let columns = 0, valid = true;
    rows.forEach((row, r) => {
        let c = 0, width = 0;
        Array.prototype.forEach.call(row.cells, cell => {
            while (occupiedUntil[c] > r) c++;
            const end = c + cell.colSpan;
            if (!cell.rowSpan || r + cell.rowSpan > rows.length) valid = false;
            for (let col = c; col < end; col++) {
                if (occupiedUntil[col] > r) valid = false;
                occupiedUntil[col] = r + Math.max(1, cell.rowSpan);
            }
            cells.push({cell, r, c});
            c = end;
        });
        // Include cells anchored on earlier rows, including rows with no td's.
        occupiedUntil.forEach((endRow, col) => { if (endRow > r) width = col + 1; });
        for (let col = 0; col < width; col++) {
            if (!(occupiedUntil[col] > r)) valid = false;
        }
        if (r && width !== columns) valid = false;
        columns = Math.max(columns, width);
    });
    return {cells, columns, valid};
}

function completeFrozenColumns(grid, requested) {
    if (!grid.valid || requested >= grid.columns) return 0;
    let count = requested, previous;
    do {
        previous = count;
        grid.cells.forEach(({cell, c}) => {
            if (c < count) count = Math.max(count, c + cell.colSpan);
        });
        if (count >= grid.columns) return 0;
    } while (previous !== count);
    return count;
}

function completeFrozenRows(rows, requested) {
    let count = requested;
    if (count >= rows.length) return 0;
    for (let row = 0; row < count; row++) {
        for (let cell of Array.prototype.slice.call(rows[row].cells)) {
            // rowspan=0 means the rest of the row group; it cannot leave a
            // reliable scrollable body in the ordinary single-group report.
            if (!cell.rowSpan) return 0;
            count = Math.max(count, row + cell.rowSpan);
            if (count >= rows.length) return 0;
        }
    }
    return count;
}

function readCount(value) {
    return /^\d+$/.test(value || '') ? Math.min(Number(value), 1000000) : 0;
}
