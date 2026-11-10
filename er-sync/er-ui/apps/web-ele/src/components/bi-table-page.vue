<script setup lang="ts">
import { Page } from '@vben/common-ui';

withDefaults(
  defineProps<{
    title: string;
    showSearch?: boolean;
    showPagination?: boolean;
  }>(),
  {
    showSearch: true,
    showPagination: true,
  },
);
</script>

<template>
  <Page
    auto-content-height
    content-class="bi-management-content"
    class="bi-management-page"
  >
    <div class="bi-page-layout">
      <aside v-if="$slots.sidebar" class="bi-sidebar-card">
        <slot name="sidebar" />
      </aside>
      <div class="bi-page-main">
        <section
          v-if="$slots.search"
          v-show="showSearch"
          class="bi-search-card"
          aria-label="查询条件"
        >
          <slot name="search" />
        </section>
        <section class="bi-table-card">
          <header class="bi-table-toolbar">
            <h2>{{ title }}</h2>
            <div v-if="$slots.toolbar" class="bi-table-tools">
              <slot name="toolbar" />
            </div>
          </header>
          <div class="bi-table-content"><slot /></div>
          <footer
            v-if="$slots.pagination && showPagination"
            class="bi-table-pagination"
          >
            <slot name="pagination" />
          </footer>
        </section>
      </div>
    </div>
  </Page>
</template>
