import { Inject } from 'vue-property-decorator'
import { Module, VuexModule, Action, Mutation } from 'vuex-module-decorators'
import { AxiosResponse, AxiosPromise } from 'axios'
import store from '@/store/'
import { LunchOffer, LunchProvider, LunchLocation } from '@/model'
import LoadingState from '@/store/LoadingState'
import Api from '@/api/LunchApi'

@Module({ store, dynamic: true, name: 'lunch' })
export default class LunchStore extends VuexModule {

  @Inject() api: Api = new Api() // bad: Vue injects into components, but not into store/modules!

  // --- providers ---

  providers: LunchProvider[] = []

  @Mutation
  mutateProviders(providers: LunchProvider[]) {
    this.providers = providers
  }

  providersByLocation(location: LunchLocation): LunchProvider[] {
    return this.providers
                    .filter(p => p.location === location.name)
  }

  // --- offers ---

  offers: LunchOffer[] = []

  @Mutation
  mutateOffers(offers: LunchOffer[]) {
    this.offers = offers
  }

  offersByDay(day: Date): LunchOffer[] {
    return this.offers
                    .filter(p => new Date(p.day).getTime() === day.getTime())
  }

  // --- locations ---

  locations: LunchLocation[] = [
    new LunchLocation('Neubrandenburg', 'NB'),
    new LunchLocation('Berlin Springpfuhl', 'B'),
  ]

  selectedLocation: LunchLocation = this.locations[0] // TODO: load from local storage

  @Mutation
  mutateSelectedLocation(location: LunchLocation) {
    this.selectedLocation = location
     // TODO: save to local storage (via action!)
  }

  // --- selected day ---

  selectedDay: Date = new Date('2018-12-03')

  // --- api call ---

  loadingState: LoadingState = LoadingState.NotStarted

  @Mutation
  mutateLoadingState(loadingState: LoadingState) {
    this.loadingState = loadingState
  }

  @Action
  async loadFromApi() {
    try {
      this.context.commit('mutateLoadingState', LoadingState.Loading)

      const providerPromise: AxiosPromise = this.api.getProviders()
      const offerPromise: AxiosPromise = this.api.getOffers()

      const providerResponse: AxiosResponse = await providerPromise
      const offerResponse: AxiosResponse = await offerPromise

      if (providerResponse.status !== 200 || offerResponse.status !== 200) {
        throw new Error('Response code must be 200 in \n' + JSON.stringify(providerResponse) + 'and in \n' + JSON.stringify(offerResponse))
      }

      this.context.commit('mutateProviders', providerResponse.data)
      this.context.commit('mutateOffers', offerResponse.data)

      this.context.commit('mutateLoadingState', LoadingState.Done)
    } catch (error) {
      console.error(error)
      this.context.commit('mutateLoadingState', LoadingState.Failed)
    }
  }

}
