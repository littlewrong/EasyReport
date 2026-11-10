<template>
  <i-frame :src="url" />
</template>
<script>
import { apiBaseURL } from '#/api/request';
import iFrame from "#/legacy/components/iFrame/index.vue"
export default {
  name: "Druid",
  components: { iFrame },
  data() {
    return {
      url: apiBaseURL + "/druid/login.html"
    }
  }
}
</script>
